    package com.example.bpmn_generator.service;

    import org.springframework.beans.factory.annotation.Value;
    import org.springframework.http.*;
    import org.springframework.stereotype.Service;
    import org.springframework.web.client.RestTemplate;

    import java.util.*;
    import java.util.regex.Pattern;
    import java.util.regex.Matcher;

    @Service
    public class ApiService {

        @Value("${openai.api.key}")
        private String apiKey;

        private final RestTemplate restTemplate = new RestTemplate();

        public Map<String, String> generate_bpmn(List<String> pathLabels, String context) {
            // 🔍 DEBUG: Log input parameters
            System.out.println("🔍 DEBUG: ApiService.generate_bpmn called");
            System.out.println("🔍 DEBUG: pathLabels = " + pathLabels);
            System.out.println("🔍 DEBUG: context = " + context);

            // 🔍 DEBUG: Check API key
            if (apiKey == null || apiKey.isEmpty() || apiKey.equals("${openai.api.key}")) {
                System.err.println("❌ ERROR: API Key tidak terkonfigurasi dengan benar!");
                System.err.println("❌ Current apiKey value: '" + apiKey + "'");
                return createErrorResponse("API Key tidak terkonfigurasi");
            }

            System.out.println("✅ DEBUG: API Key tersedia (length: " + apiKey.length() + ")");

            String readablePath = String.join(" -> ", pathLabels);
            boolean hasLanes = pathLabels.stream().anyMatch(label -> label.contains("[") && label.contains("]"));
            String cleanPathForDescription = createCleanPathForDescription(pathLabels);
            String prompt = createOptimizedPrompt(context, readablePath, cleanPathForDescription, hasLanes);

            // 🔍 DEBUG: Log prompt
            System.out.println("🔍 DEBUG: Generated prompt length: " + prompt.length());
            System.out.println("🔍 DEBUG: Prompt preview: " + prompt.substring(0, Math.min(200, prompt.length())));

            // 🔍 DEBUG: Prepare request
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", "gpt-4o");
            body.put("temperature", 0.1);
            body.put("max_tokens", 2000); 
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "Anda adalah asisten QA profesional yang sangat terstruktur. Ikuti format yang diminta dengan tepat dan konsisten."),
                    Map.of("role", "user", "content", prompt)
            ));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            System.out.println("🚀 DEBUG: Sending request to OpenAI API...");

            try {
                // 🔍 DEBUG: Make API call with detailed logging
                ResponseEntity<Map> response = restTemplate.postForEntity(
                        "https://api.openai.com/v1/chat/completions", request, Map.class
                );

                System.out.println("✅ DEBUG: API call successful!");
                System.out.println("🔍 DEBUG: Response status: " + response.getStatusCode());
                System.out.println("🔍 DEBUG: Response body type: " + response.getBody().getClass());

                // 🔍 DEBUG: Log response structure
                Map responseBody = response.getBody();
                if (responseBody != null) {
                    System.out.println("🔍 DEBUG: Response keys: " + responseBody.keySet());

                    if (responseBody.containsKey("choices")) {
                        List<Map> choices = (List<Map>) responseBody.get("choices");
                        System.out.println("🔍 DEBUG: Choices count: " + choices.size());

                        if (!choices.isEmpty()) {
                            Map firstChoice = choices.get(0);
                            System.out.println("🔍 DEBUG: First choice keys: " + firstChoice.keySet());

                            if (firstChoice.containsKey("message")) {
                                Map message = (Map) firstChoice.get("message");
                                String content = message.get("content").toString();
                                System.out.println("🔍 DEBUG: Content length: " + content.length());
                                System.out.println("🔍 DEBUG: Content preview: " + content.substring(0, Math.min(300, content.length())));

                                // Parse result
                                Map<String, String> result = parseImprovedResult(content);
                                System.out.println("🔍 DEBUG: Parsed result keys: " + result.keySet());

                                // Fix format
                                if (hasLanes) {
                                    result = fixLaneFormat(result, pathLabels);
                                } else {
                                    result = fixNonLaneFormat(result, pathLabels);
                                }

                                System.out.println("✅ DEBUG: Successfully processed API response");
                                return result;
                            }
                        }
                    }
                }

                System.err.println("❌ ERROR: Unexpected response structure");
                return createErrorResponse("Response structure tidak valid");

            } catch (Exception e) {
                System.err.println("❌ ERROR: Exception during API call");
                System.err.println("❌ Exception type: " + e.getClass().getSimpleName());
                System.err.println("❌ Exception message: " + e.getMessage());
                e.printStackTrace();

                // 🔍 DEBUG: Log specific error types
                if (e.getMessage() != null) {
                    if (e.getMessage().contains("401")) {
                        System.err.println("🚨 LIKELY CAUSE: Invalid API Key");
                    } else if (e.getMessage().contains("403")) {
                        System.err.println("🚨 LIKELY CAUSE: API access forbidden");
                    } else if (e.getMessage().contains("timeout")) {
                        System.err.println("🚨 LIKELY CAUSE: Network timeout");
                    } else if (e.getMessage().contains("ConnectException")) {
                        System.err.println("🚨 LIKELY CAUSE: Network connectivity issue");
                    }
                }

                return createErrorResponse("API call gagal: " + e.getMessage());
            }
        }
        // 🔹 Buat path yang bersih untuk deskripsi (tanpa format lane)
        private String createCleanPathForDescription(List<String> pathLabels) {
            return pathLabels.stream()
                    .map(label -> {
                        // Remove lane format dari deskripsi
                        if (label.contains("[") && label.contains("]")) {
                            return label.replaceAll("\\[.*?\\]\\s*", "").trim();
                        }
                        return label;
                    })
                    .collect(java.util.stream.Collectors.joining(" -> "));
        }

        // 🔹 Buat prompt yang dioptimalkan
        private String createOptimizedPrompt(String context, String readablePath, String cleanPath, boolean hasLanes) {
            String laneInstruction = hasLanes ?
                    "\n\nPENTING UNTUK SCENARIO_STEP: Dalam langkah-langkah SCENARIO_STEP, WAJIB gunakan format yang sama dengan jalur yang diberikan. Jika ada [Lane], pertahankan format [Lane] dan buat kalimat yang menjelaskan apa yang dilakukan oleh lane tersebut secara detail dan naratif." :
                    "\n\nPENTING UNTUK SCENARIO_STEP: Buat langkah-langkah yang naratif dan menjelaskan tindakan yang dilakukan secara detail. Jangan hanya sebutkan nama aktivitas, tapi jelaskan apa yang dilakukan.";

            String stepExamples = hasLanes ?
                    """
                    
        CONTOH FORMAT SCENARIO_STEP YANG BENAR:
        - JIKA ADA LANE: "1. [Sekretaris] Kirim barang ke alamat tujuan yang telah ditentukan dengan memastikan kemasan sesuai standar"
        - JIKA ADA LANE: "2. [Gudang] Siapkan barang untuk penjemputan dengan melakukan pengecekan kualitas dan kelengkapan"
        
        JANGAN HANYA MENULIS:
        - "1. [Sekretaris] Kirim Barang" 
        - "2. [Gudang] Siapkan Barang" 
        
        TAPI HARUS SEPERTI INI:
        - "1. [Sekretaris] Melakukan pengiriman barang ke alamat tujuan dengan memastikan dokumentasi lengkap" 
        - "2. [Gudang] Menyiapkan barang untuk dijemput dengan melakukan pengecekan kualitas terlebih dahulu" 
                    """ :
                    """
                    
        CONTOH FORMAT SCENARIO_STEP YANG BENAR:
        - TANPA LANE: "1. Lakukan pengiriman barang ke alamat tujuan dengan memastikan kemasan sesuai standar"
        - TANPA LANE: "2. Siapkan barang untuk penjemputan dengan melakukan pengecekan kualitas dan kelengkapan"
        
        JANGAN HANYA MENULIS:
        - "1. Kirim Barang" 
        - "2. Siapkan Barang" 
        
        TAPI HARUS SEPERTI INI:
        - "1. Lakukan pengiriman barang ke alamat tujuan dengan memastikan dokumentasi lengkap" 
        - "2. Siapkan barang untuk dijemput dengan melakukan pengecekan kualitas terlebih dahulu" 
                    """;

            return String.format("""
    Anda adalah asisten QA yang membuat skenario pengujian untuk proses bisnis BPMN.
    
    KONTEKS PROSES: %s
    
    JALUR UNTUK SCENARIO_STEP: %s
    JALUR UNTUK DESKRIPSI: %s%s
    
    INSTRUKSI:
    1. Buat deskripsi skenario yang menjelaskan alur proses secara naratif dan mengalir tanpa menyebutkan format teknis [Lane]
    2. Buat langkah-langkah pengujian yang NARATIF dan DETAIL - jelaskan tindakan yang dilakukan, bukan hanya nama aktivitas
    3. Berikan contoh data input dalam format JSON yang valid
    4. Berikan expected result dalam format JSON yang valid
    5. Ringkas deskripsi tersebut menjadi satu paragraf ringkasan yang bernaratif dan mudah dipahami(===SUMMARY===)
    WAJIB MENGIKUTI FORMAT INI PERSIS:
    
    ===SUMMARY===
    [tuliskan deskripsi singkat tentang alur proses bisnis proses awal hingga akhir yang menggambarakan keseluruhan proses di suatu path buat jadi naratif yaa]
    
    ===DESKRIPSI===
    [Tuliskan deskripsi skenario pengujian yang mengalir secara naratif. Fokus pada alur proses bisnis, bukan format teknis. Jangan sebutkan format [Lane] dalam deskripsi]
    
    ===SCENARIO_STEP===
    [Buat langkah-langkah yang numbered, naratif, dan menjelaskan tindakan yang dilakukan secara detail]
    1. [Penjelasan detail tindakan yang dilakukan pada langkah pertama]
    2. [Penjelasan detail tindakan yang dilakukan pada langkah kedua]
    dst...
    
    ===INPUT_DATA===
    {
      "field1": "value1",
      "field2": "value2"
    }
    
    ===EXPECTED_RESULT===
    {
      "status": "success",
      "message": "Proses berhasil",
      "data": {}
    }
    
    %s
    
    PENTING: 
    - Deskripsi harus mengalir secara naratif tanpa format teknis
    - Scenario_step harus NARATIF dan menjelaskan tindakan yang dilakukan secara detail
    - Jangan hanya menyebutkan nama aktivitas, tapi jelaskan apa yang dilakukan
    - Setiap step harus menjelaskan HOW dan WHAT, bukan hanya WHAT
    """, context, readablePath, cleanPath, laneInstruction, stepExamples);
        }

        // 🔹 Method untuk memperbaiki format lane
        private Map<String, String> fixLaneFormat(Map<String, String> result, List<String> originalPathLabels) {
            try {
                String scenarioStep = result.get("scenario_step");
                if (scenarioStep == null || scenarioStep.trim().isEmpty()) {
                    return result;
                }

                String[] steps = scenarioStep.split("\n");
                List<String> fixedSteps = new ArrayList<>();

                for (int i = 0; i < steps.length && i < originalPathLabels.size(); i++) {
                    String step = steps[i].trim();
                    String originalLabel = originalPathLabels.get(i);

                    // Skip empty steps
                    if (step.isEmpty()) continue;

                    // Remove existing numbering to get clean content
                    String cleanStep = step.replaceAll("^\\d+\\.\\s*", "").trim();

                    // Jika original label punya lane
                    if (originalLabel.contains("[") && originalLabel.contains("]")) {
                        // Extract lane dan activity dari original
                        String lane = originalLabel.substring(originalLabel.indexOf("["), originalLabel.indexOf("]") + 1);
                        String activityName = originalLabel.substring(originalLabel.indexOf("]") + 1).trim();

                        // Buat step yang naratif dengan lane
                        String narrativeStep;
                        if (cleanStep.contains("[") && cleanStep.contains("]")) {
                            // GPT sudah memberikan format lane, pastikan naratif
                            narrativeStep = cleanStep;
                        } else {
                            // GPT tidak memberikan lane, buat step naratif dengan lane
                            narrativeStep = createNarrativeStepWithLane(lane, activityName, cleanStep);
                        }

                        fixedSteps.add(String.format("%d. %s", i + 1, narrativeStep));
                    } else {
                        // Tidak ada lane, pastikan step naratif
                        String narrativeStep = createNarrativeStep(originalLabel, cleanStep);
                        fixedSteps.add(String.format("%d. %s", i + 1, narrativeStep));
                    }
                }

                result.put("scenario_step", String.join("\n", fixedSteps));
                System.out.println("✅ Format lane diperbaiki untuk scenario steps");

            } catch (Exception e) {
                System.err.println("❌ Error fixing lane format: " + e.getMessage());
            }

            return result;
        }

        // 🔹 Method untuk membuat step naratif dengan lane
        private String createNarrativeStepWithLane(String lane, String activityName, String gptContent) {
            // Jika GPT sudah memberikan konten yang baik, gunakan itu
            if (gptContent.length() > activityName.length() * 2) {
                return lane + " " + gptContent;
            }

            // Jika tidak, buat step naratif berdasarkan activity name
            String narrativeAction = createNarrativeAction(activityName);
            return lane + " " + narrativeAction;
        }

        // 🔹 Method untuk membuat step naratif tanpa lane
        private String createNarrativeStep(String originalLabel, String gptContent) {
            // Jika GPT sudah memberikan konten yang baik, gunakan itu
            if (gptContent.length() > originalLabel.length() * 2) {
                return gptContent;
            }

            // Jika tidak, buat step naratif berdasarkan original label
            return createNarrativeAction(originalLabel);
        }

        // 🔹 Method untuk membuat aksi naratif
        private String createNarrativeAction(String activityName) {
            String activity = activityName.toLowerCase().trim();

            if (activity.contains("kirim") || activity.contains("send")) {
                return "Melakukan pengiriman " + activityName.toLowerCase() + " ke tujuan yang telah ditentukan dengan memastikan dokumentasi lengkap";
            } else if (activity.contains("siap") || activity.contains("prepare")) {
                return "Menyiapkan " + activityName.toLowerCase() + " dengan melakukan pengecekan kualitas dan kelengkapan";
            } else if (activity.contains("klarifikasi") || activity.contains("clarify")) {
                return "Melakukan klarifikasi terhadap " + activityName.toLowerCase() + " untuk memastikan informasi yang akurat";
            } else if (activity.contains("cek") || activity.contains("check")) {
                return "Melakukan pengecekan " + activityName.toLowerCase() + " secara menyeluruh untuk memastikan kesesuaian";
            } else if (activity.contains("validasi") || activity.contains("validate")) {
                return "Melakukan validasi " + activityName.toLowerCase() + " untuk memastikan data yang benar";
            } else if (activity.contains("terima") || activity.contains("receive")) {
                return "Menerima " + activityName.toLowerCase() + " dan melakukan verifikasi kelengkapan";
            } else if (activity.contains("proses") || activity.contains("process")) {
                return "Memproses " + activityName.toLowerCase() + " sesuai dengan prosedur yang telah ditetapkan";
            } else if (activity.contains("pilih") || activity.contains("select")) {
                return "Memilih " + activityName.toLowerCase() + " berdasarkan kriteria yang telah ditentukan";
            } else if (activity.contains("dapat") || activity.contains("get")) {
                return "Mendapatkan " + activityName.toLowerCase() + " dari sumber yang terpercaya";
            } else if (activity.contains("konfirmasi") || activity.contains("confirm")) {
                return "Mengkonfirmasi " + activityName.toLowerCase() + " untuk memastikan akurasi informasi";
            } else if (activity.contains("pesan") || activity.contains("order")) {
                return "Melakukan pemesanan " + activityName.toLowerCase() + " dengan mengisi formulir yang diperlukan";
            } else if (activity.contains("kemas") || activity.contains("pack")) {
                return "Melakukan pengemasan " + activityName.toLowerCase() + " sesuai dengan standar yang ditetapkan";
            } else if (activity.contains("jemput") || activity.contains("pickup")) {
                return "Menyiapkan " + activityName.toLowerCase() + " untuk dijemput oleh pihak terkait";
            } else if (activity.contains("?")) {
                return "Mengevaluasi " + activityName.replace("?", "").toLowerCase() + " untuk menentukan langkah selanjutnya";
            } else {
                return "Melakukan " + activityName.toLowerCase() + " sesuai dengan prosedur yang telah ditetapkan";
            }
        }

        // 🔹 Method untuk memperbaiki format non-lane
        private Map<String, String> fixNonLaneFormat(Map<String, String> result, List<String> originalPathLabels) {
            try {
                String scenarioStep = result.get("scenario_step");
                if (scenarioStep == null || scenarioStep.trim().isEmpty()) {
                    // Generate manual narrative steps if empty
                    List<String> manualSteps = new ArrayList<>();
                    for (int i = 0; i < originalPathLabels.size(); i++) {
                        String label = originalPathLabels.get(i);
                        String narrativeStep = createNarrativeAction(label);
                        manualSteps.add(String.format("%d. %s", i + 1, narrativeStep));
                    }
                    result.put("scenario_step", String.join("\n", manualSteps));
                    return result;
                }

                String[] steps = scenarioStep.split("\n");
                List<String> fixedSteps = new ArrayList<>();

                for (int i = 0; i < originalPathLabels.size(); i++) {
                    String originalLabel = originalPathLabels.get(i);

                    if (i < steps.length && !steps[i].trim().isEmpty()) {
                        String step = steps[i].trim();
                        // Remove existing numbering
                        String cleanStep = step.replaceAll("^\\d+\\.\\s*", "").trim();

                        // Pastikan step naratif
                        String narrativeStep = createNarrativeStep(originalLabel, cleanStep);
                        fixedSteps.add(String.format("%d. %s", i + 1, narrativeStep));
                    } else {
                        // Use narrative action for original label if step is missing
                        String narrativeStep = createNarrativeAction(originalLabel);
                        fixedSteps.add(String.format("%d. %s", i + 1, narrativeStep));
                    }
                }

                result.put("scenario_step", String.join("\n", fixedSteps));
                System.out.println("✅ Format non-lane diperbaiki untuk scenario steps");

            } catch (Exception e) {
                System.err.println("❌ Error fixing non-lane format: " + e.getMessage());
            }

            return result;
        }

        // 🔹 FIXED: Method untuk parsing result dengan perbaikan handling summary
        private Map<String, String> parseImprovedResult(String gptOutput) {
            Map<String, String> result = new HashMap<>();

            try {
                // 🔹 Debug output untuk troubleshooting
                System.out.println("🔍 GPT Output length: " + gptOutput.length());
                System.out.println("🔍 GPT Output preview: " + gptOutput.substring(0, Math.min(500, gptOutput.length())));

                // 🔹 Parsing dengan regex yang lebih robust
                String summary = extractSection(gptOutput, "===SUMMARY===", "===DESKRIPSI===");
                System.out.println("🔍 Extracted summary (method 1): '" + summary + "'");

                if (summary == null || summary.isBlank()) {
                    summary = extractSection(gptOutput, "===SUMMARY===", "===DESCRIPTION===");
                    System.out.println("🔍 Extracted summary (method 2): '" + summary + "'");
                }

                if (summary == null || summary.isBlank()) {
                    summary = extractSection(gptOutput, "===SUMMARY===", null);
                    System.out.println("🔍 Extracted summary (method 3): '" + summary + "'");
                }

                String description = extractSection(gptOutput, "===DESKRIPSI===", "===SCENARIO_STEP===");
                if (description == null || description.isBlank()) {
                    description = extractSection(gptOutput, "===DESCRIPTION===", "===SCENARIO_STEP===");
                }

                String scenarioStep = extractSection(gptOutput, "===SCENARIO_STEP===", "===INPUT_DATA===");
                String inputData = extractSection(gptOutput, "===INPUT_DATA===", "===EXPECTED_RESULT===");
                String expectedResult = extractSection(gptOutput, "===EXPECTED_RESULT===", null);

                // 🔹 Validasi dan clean up dengan method yang tepat
                result.put("description", cleanDescription(description));
                result.put("scenario_step", cleanScenarioSteps(scenarioStep));
                result.put("input_data", cleanJson(inputData));
                result.put("expected_result", cleanJson(expectedResult));
                result.put("summary", cleanSummary(summary)); // ✅ FIXED: Gunakan cleanSummary bukan cleanJson

                System.out.println("🔍 Final summary in result: '" + result.get("summary") + "'");

                // 🔹 Validasi apakah semua field terisi
                if (result.get("description").trim().isEmpty()) {
                    result.put("description", "Skenario pengujian untuk proses bisnis yang mencakup serangkaian langkah pengujian yang harus dilakukan secara berurutan untuk memastikan proses berjalan dengan benar.");
                }

                if (result.get("summary").trim().isEmpty()) {
                    result.put("summary", "Pengujian alur proses bisnis end-to-end untuk memastikan setiap langkah berjalan sesuai prosedur yang telah ditetapkan.");
                }

                System.out.println("✅ Parsing berhasil untuk response GPT");

            } catch (Exception e) {
                System.err.println("❌ Error parsing GPT response: " + e.getMessage());
                e.printStackTrace();
                return createErrorResponse("Gagal parsing response GPT: " + e.getMessage());
            }

            return result;
        }

        // 🔹 NEW: Method khusus untuk membersihkan summary
        private String cleanSummary(String summary) {
            if (summary == null || summary.trim().isEmpty()) {
                return "Pengujian alur proses bisnis end-to-end untuk memastikan setiap langkah berjalan sesuai prosedur yang telah ditetapkan.";
            }

            try {
                // Remove markdown formatting dan section markers
                String cleaned = summary.trim()
                        .replaceAll("\\*\\*.*?\\*\\*", "") // Remove bold formatting
                        .replaceAll("\\*(.+?)\\*", "$1") // Remove italic formatting
                        .replaceAll("===.*?===", "") // Remove section markers
                        .replaceAll("```.*?```", "") // Remove code blocks
                        .replaceAll("\\n\\s*\\n", " ") // Replace multiple newlines with space
                        .replaceAll("\\s+", " ") // Replace multiple spaces with single space
                        .trim();

                // Pastikan tidak kosong setelah cleaning
                if (cleaned.isEmpty()) {
                    return "Pengujian alur proses bisnis end-to-end untuk memastikan setiap langkah berjalan sesuai prosedur yang telah ditetapkan.";
                }

                return cleaned;
            } catch (Exception e) {
                System.err.println("❌ Error cleaning summary: " + e.getMessage());
                return "Pengujian alur proses bisnis end-to-end untuk memastikan setiap langkah berjalan sesuai prosedur yang telah ditetapkan.";
            }
        }

        // 🔹 IMPROVED: Method extractSection dengan better error handling
        private String extractSection(String text, String startMarker, String endMarker) {
            try {
                if (text == null || text.trim().isEmpty()) {
                    return "";
                }

                int startIndex = text.indexOf(startMarker);
                if (startIndex == -1) {
                    System.out.println("🔍 Marker not found: " + startMarker);
                    return "";
                }

                startIndex += startMarker.length();

                int endIndex;
                if (endMarker != null) {
                    endIndex = text.indexOf(endMarker, startIndex);
                    if (endIndex == -1) {
                        endIndex = text.length();
                    }
                } else {
                    endIndex = text.length();
                }

                String extracted = text.substring(startIndex, endIndex).trim();
                System.out.println("🔍 Extracted section for " + startMarker + ": '" + extracted.substring(0, Math.min(100, extracted.length())) + "...'");

                return extracted;
            } catch (Exception e) {
                System.err.println("❌ Error extracting section " + startMarker + ": " + e.getMessage());
                return "";
            }
        }

        private String cleanDescription(String description) {
            if (description == null || description.trim().isEmpty()) return "";

            // Remove any unwanted formatting and make it flow naturally
            return description.trim()
                    .replaceAll("\\*\\*.*?\\*\\*", "") // Remove bold formatting
                    .replaceAll("\\n\\s*\\n", "\n") // Remove extra newlines
                    .replaceAll("- Jika jalur:.*?→.*?\\n", "") // Remove technical format examples
                    .replaceAll("===.*?===", "") // Remove any leaked section markers
                    .replaceAll("```.*?```", "") // Remove code blocks
                    .trim();
        }

        private String cleanScenarioSteps(String steps) {
            if (steps == null || steps.trim().isEmpty()) return "";

            // 🔹 Pastikan format step yang konsisten
            String[] lines = steps.split("\n");
            StringBuilder cleanSteps = new StringBuilder();

            int stepNumber = 1;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Remove any unwanted formatting
                line = line.replaceAll("\\*\\*", ""); // Remove bold
                line = line.replaceAll("===.*?===", ""); // Remove section markers
                line = line.replaceAll("```.*?```", ""); // Remove code blocks

                // 🔹 Standardize numbering
                if (line.matches("^\\d+\\.\\s*.*")) {
                    String content = line.replaceAll("^\\d+\\.\\s*", "").trim();
                    if (!content.isEmpty()) {
                        cleanSteps.append(stepNumber).append(". ").append(content).append("\n");
                        stepNumber++;
                    }
                } else if (line.startsWith("-")) {
                    String content = line.replaceAll("^-\\s*", "").trim();
                    if (!content.isEmpty()) {
                        cleanSteps.append(stepNumber).append(". ").append(content).append("\n");
                        stepNumber++;
                    }
                } else if (!line.isEmpty()) {
                    cleanSteps.append(stepNumber).append(". ").append(line).append("\n");
                    stepNumber++;
                }
            }

            return cleanSteps.toString().trim();
        }

        private String cleanJson(String json) {
            if (json == null || json.trim().isEmpty()) return "{}";

            try {
                // 🔹 Remove markdown code blocks and unwanted formatting
                json = json.replaceAll("```json", "").replaceAll("```", "").trim();
                json = json.replaceAll("===.*?===", "").trim();

                // 🔹 Basic validation - check if it looks like JSON
                if (!json.startsWith("{") || !json.endsWith("}")) {
                    return "{}";
                }

                return json;
            } catch (Exception e) {
                return "{}";
            }
        }

        private Map<String, String> createErrorResponse(String errorMessage) {
            Map<String, String> error = new HashMap<>();
            error.put("description", "❌ Gagal generate skenario: " + errorMessage);
            error.put("scenario_step", "1. Terjadi kesalahan dalam generate skenario\n2. Silakan coba lagi");
            error.put("input_data", "{}");
            error.put("expected_result", "{\"status\": \"error\", \"message\": \"Gagal generate skenario\"}");
            error.put("summary", "Terjadi kesalahan dalam generate skenario pengujian");
            return error;
        }
    }
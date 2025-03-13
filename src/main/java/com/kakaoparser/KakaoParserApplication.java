package com.kakaoparser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandLine.Command(
        name = "kakao2jsonl",
        description = "카카오톡 채팅을 JSONL 형식으로 변환",
        mixinStandardHelpOptions = true,
        version = "1.0"
)
public class KakaoParserApplication implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(KakaoParserApplication.class);
    private static final Pattern MESSAGE_PATTERN = Pattern.compile("\\[(.*?)] \\[(.*?)] (.*)");
    private static final Pattern DATE_PATTERN = Pattern.compile("-{3,}\\s*\\d{4}년\\s*\\d{1,2}월\\s*\\d{1,2}일\\s*[월화수목금토일]요일\\s*-{3,}\\n");

    @CommandLine.Option(names = {"-i", "--input"},
            description = "카카오톡 채팅 텍스트 파일 경로",
            required = true)
    private File inputFile;

    @CommandLine.Option(names = {"-o", "--output"},
            description = "출력할 JSONL 파일 경로",
            defaultValue = "${user.home}/Desktop/output.jsonl")
    private File outputFile;

    @CommandLine.Option(names = {"--user"},
            description = "user 역할로 지정할 사용자 이름",
            required = true)
    private String userName;

    @CommandLine.Option(names = {"--model"},
            description = "model 역할로 지정할 사용자 이름",
            required = true)
    private String modelName;

    static class TrainingFormat {
        List<Content> contents;

        TrainingFormat() {
            this.contents = new ArrayList<>();
        }
    }

    static class Content {
        String role;
        List<Part> parts;

        Content(String role, String text) {
            this.role = role;
            this.parts = new ArrayList<>();
            this.parts.add(new Part(text));
        }
    }

    static class Part {
        String text;

        Part(String text) {
            this.text = text;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new KakaoParserApplication()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            logger.info("변환 시작: {} -> {}", inputFile.getPath(), outputFile.getPath());

            List<String> processedMessages = processInputFile(inputFile);
            writeJsonlOutput(processedMessages, outputFile);

            logger.info("변환 완료: {} 개의 메시지 처리됨", processedMessages.size());
        } catch (Exception e) {
            logger.error("변환 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("변환 실패", e);
        }
    }

    private List<String> processInputFile(File inputFile) throws IOException {
        String content = Files.readString(inputFile.toPath(), StandardCharsets.UTF_8);
        String filteredContent = filterContent(content);
        return extractMessages(filteredContent);
    }

    private String filterContent(String content) {
        return content
                .replaceAll("\\[.*?] \\[.*?] (사진|동영상|음성메시지)(\\s*\\d*장?)?\\n", "")
                .replaceAll(DATE_PATTERN.pattern(), "");
    }

    private List<String> extractMessages(String filteredContent) {
        List<String> messages = new ArrayList<>();
        StringBuilder messageBuilder = new StringBuilder();
        String currentUser = null;

        for (String line : filteredContent.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher matcher = MESSAGE_PATTERN.matcher(line);
            if (matcher.find()) {
                if (!messageBuilder.isEmpty() && currentUser != null) {
                    messages.add(currentUser + " " + messageBuilder.toString().trim());
                    messageBuilder = new StringBuilder();
                }
                currentUser = "[" + matcher.group(1) + "]";
                messageBuilder.append(matcher.group(3));
            } else {
                if (!messageBuilder.isEmpty()) {
                    messageBuilder.append("\n").append(line);
                }
            }
        }

        if (!messageBuilder.isEmpty() && currentUser != null) {
            messages.add(currentUser + " " + messageBuilder.toString().trim());
        }

        return messages;
    }

    private void writeJsonlOutput(List<String> messages, File outputFile) throws IOException {
        Gson gson = new GsonBuilder().create();

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile.toPath(), StandardCharsets.UTF_8)) {
            for (int i = 0; i < messages.size() - 1; i += 2) {
                TrainingFormat format = createTrainingFormat(messages.get(i),
                        i + 1 < messages.size() ? messages.get(i + 1) : null);
                if (format != null) {
                    writer.write(gson.toJson(format));
                    writer.newLine();
                }
            }
        }
    }

    private TrainingFormat createTrainingFormat(String currentMessage, String nextMessage) {
        String[] currentParts = currentMessage.split("] ", 2);
        if (currentParts.length < 2) return null;

        TrainingFormat format = new TrainingFormat();
        String currentRole = determineRole(currentParts[0] + "]");
        format.contents.add(new Content(currentRole, currentParts[1].trim()));

        if (nextMessage != null) {
            String[] nextParts = nextMessage.split("] ", 2);
            if (nextParts.length >= 2) {
                String nextRole = "user".equals(currentRole) ? "model" : "user";
                format.contents.add(new Content(nextRole, nextParts[1].trim()));
            }
        }

        return format;
    }

    private String determineRole(String user) {
        if (user.equals("[" + userName + "]")) return "user";
        if (user.equals("[" + modelName + "]")) return "model";
        return "user"; // 기본값
    }

}

package com.han.youtube.service.Impl;

import com.google.api.services.youtube.model.VideoSnippet;
import com.han.youtube.Domain.ReceiveId;
import com.han.youtube.Dto.GazeAnalysisResult;
import com.han.youtube.Dto.ReceiveIdDto;
import com.han.youtube.Dto.VideoIdRequest;
import com.han.youtube.Repository.MongoRepository;

import com.han.youtube.service.GazeDataService;
import com.han.youtube.service.YoutubeService;
import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.google.api.services.youtube.model.Video;

import java.io.*;
import java.nio.file.Paths;

import java.util.*;

@Service
@RequiredArgsConstructor
public class GazeDataServiceImpl implements GazeDataService {

    @Autowired
    private YoutubeService youtubeService;
    private final MongoRepository mongoRepository;


    //평균점수 리스트화
    public List<Integer> listScore(List<List<Object>> scoreList) {
        List<Integer> listscore = new ArrayList<>();

        for (List<Object> score : scoreList) {
            if (score.size() > 2 && score.get(2) instanceof Number) {
                listscore.add(((Number) score.get(2)).intValue());
            }
        }
        return listscore;
    }


    //파이썬 코드 실행
    @Transactional
    @Override
    public GazeAnalysisResult runPythonScript(String videoId, String videoCSV, String videoWidth, String videoHeight) {
        try {
            String python = "python";

            // 파이썬 파일 경로 설정
            File currentDir = new File("");
            String rootPath = currentDir.getAbsoluteFile().getParent();  // youtube-seeso-demo 경로
            String fileDirectory = Paths.get(rootPath, "analysis").normalize().toString();
            String scriptPath = Paths.get(fileDirectory, "video_analysis.py").toString();

            int width = (int) Double.parseDouble(videoWidth);
            int height = (int) Double.parseDouble(videoHeight);


            List<String> arguments = new ArrayList<>();
            arguments.add(python);
            arguments.add(scriptPath);
            arguments.add(videoId);
            arguments.add(videoCSV);
            arguments.add(String.valueOf(width));
            arguments.add(String.valueOf(height));

            ProcessBuilder pb = new ProcessBuilder(arguments);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 파이썬 출력 및 에러 출력
            BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;

            // 출력된 JSON 데이터 저장
            StringBuilder jsonOutput = new StringBuilder();

            // JSON 시작점 찾기
            boolean jsonStarted = false;
            while ((line = stdout.readLine()) != null) {
                System.out.println(line);
                // JSON이 시작되는 지점부터 문자열 추출
                if (line.trim().startsWith("{")) {
                    jsonStarted = true;  // JSON 시작
                }

                // JSON 데이터 저장
                if (jsonStarted) {
                    jsonOutput.append(line.trim());  // JSON 부분만 추출
                }
            }

            // 에러 출력
            while ((line = stderr.readLine()) != null) {
                System.err.println("ERROR: " + line);
            }

            int exitCode = process.waitFor();
            System.out.println("스크립트 exit 코드 :" + exitCode);

            // JSON 파싱
            String jsonString = jsonOutput.toString();
            JSONObject result = new JSONObject(jsonString);

            JSONArray jsonArray = result.getJSONArray("attention_score_list");
            List<List<Object>> attentionScoreList = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONArray innerArray = jsonArray.getJSONArray(i);
                List<Object> innerList = new ArrayList<>();

                for (int j = 0; j < innerArray.length(); j++) {
                    innerList.add(innerArray.get(j));
                }
                attentionScoreList.add(innerList);
            }

            List<Integer> scoreList = listScore(attentionScoreList);
            String videoPoint = result.getString("video_point");

            return new GazeAnalysisResult(attentionScoreList, videoPoint, scoreList);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

    }

    @SuppressWarnings("unchecked")
    @Transactional
    @Override
    public void saveGazeData(Map<String, Object> payload) throws IOException {  // 시선 데이터 저장
        String videoId = (String) payload.get("videoId");
        String watchDate = (String) payload.get("watchDate");

        // 비디오 크기 값 videoFrame.get("width"), videoFrame.get("height")
        Map<String, Object> videoFrame = null;
        if (payload.get("videoFrame") instanceof Map) {
            videoFrame = (Map<String, Object>) payload.get("videoFrame");
        }

        // 시선 데이터
        List<Map<String, Object>> gazeData = null;
        if (payload.get("gazeData") instanceof List) {
            gazeData = (List<Map<String, Object>>) payload.get("gazeData");
        }

        // CSV 파일 경로 설정
        // 현재 애플리케이션의 루트 경로를 가져오기
        File currentDir = new File("");
        String rootPath = currentDir.getAbsoluteFile().getParent();  // SeeU 경로

        System.out.println("루트패스 : "+rootPath);
        // 항상 Data/GazeData 경로를 지정
        String filePath = Paths.get(rootPath, "frontend", "public", "data", "GazeData").normalize().toString();
        String videoCSV = Paths.get(filePath, videoId + "_" + watchDate + ".csv").toString();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(videoCSV))) {
            // 헤더
            writer.append("Time,X,Y,Attention\n");

            // 시선 좌표 데이터를 CSV 파일에 작성
            for (Map<String, Object> record : gazeData) {
                writer.append(record.get("time") != null ? record.get("time").toString() : "null")
                        .append(",")
                        .append(record.get("x") != null ? record.get("x").toString() : "null")
                        .append(",")
                        .append(record.get("y") != null ? record.get("y").toString() : "null")
                        .append(",")
                        .append(record.get("attention") != null ? record.get("attention").toString() : "3")
                        .append("\n");
            }

            writer.flush();  // 파일에 데이터 저장
        }

        // Python 스크립트 실행 후 영상 분석 결과 받아오기
        GazeAnalysisResult result = runPythonScript(videoId, videoCSV, String.valueOf(videoFrame.get("width")), String.valueOf(videoFrame.get("height")));

        if (result != null) {
            System.out.println("Attention Score List: " + result.getAttentionScoreList());
            System.out.println("Video Gaze Visualization: " + result.getGazeVisualization());
        } else {
            System.out.println("Python 스크립트 실행 중 오류 발생");
        }

        // youtubeService.getVideoById 사용해서 영상 정보 불러오기
        Video video = youtubeService.getVideoById(videoId);
        if (video != null) {
            VideoSnippet snippet = video.getSnippet();

            LinkedHashMap<String, Object> snippetMap = new LinkedHashMap<>();
            snippetMap.put("title", snippet.getTitle());
            snippetMap.put("thumbnails", snippet.getThumbnails());

            ReceiveIdDto receiveIdDto = new ReceiveIdDto();
            ReceiveId receiveId = receiveIdDto.toEntity(
                    videoId,
                    watchDate,
                    snippetMap,
                    result != null ? result.getAttentionScoreList() : null,
                    result != null ? result.getGazeVisualization() : null,
                    result != null ? result.getAttentionList() : null
            );


            mongoRepository.save(receiveId);
        } else {
            System.out.println("해당 ID의 영상을 찾지 못했습니다.");
        }
    }

    @Transactional
    @Override
    public List<ReceiveIdDto> dbData() {
        return mongoRepository.findAllBy();
    }

    @Transactional
    @Override
    public List<List<Object>> averScore(VideoIdRequest videoIdRequest) {

        String videoId = videoIdRequest.getVideoId();
        List<ReceiveId> videoScores = mongoRepository.findByVideoId(videoId);

        int attentionSize = videoScores.get(0).getAttentionList().size();
        List<Double> averageAttentionList = new ArrayList<>();

        for (int i = 0; i < attentionSize; i++) {
            int sum = 0;
            int count = 0;
            for (ReceiveId receiveId : videoScores) {
                if (i < receiveId.getAttentionList().size()) {
                    sum += receiveId.getAttentionList().get(i);
                    count++;
                }
            }
            double aver = count > 0 ? sum / (double) count : 0.0;
            double roundAver = Math.round(aver * 100.0) / 100.0;  //소수점 2자리에서 반올림
            averageAttentionList.add(roundAver);
        }

        List<List<Object>> resultList = new ArrayList<>();
        List<List<Object>> scoreList = videoScores.get(0).getScoreList();

        for (int i = 0; i < averageAttentionList.size(); i++) {
            String splitScene = (String) scoreList.get(i).get(0); // 영상 주소
            String splitThumb = (String) scoreList.get(i).get(1); // 썸네일 주소
            Double averScore = averageAttentionList.get(i); // 평균값

            List<Object> splitData = Arrays.asList(splitScene, splitThumb, averScore);
            resultList.add(splitData);
        }

        // 내림차순 정렬
        resultList.sort((list1, list2) -> ((Double) list2.get(2)).compareTo((Double) list1.get(2)));

        return resultList;
    }


}
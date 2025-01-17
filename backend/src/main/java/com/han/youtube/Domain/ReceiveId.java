package com.han.youtube.Domain;

import jakarta.persistence.Id;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Document(collection = "Capstone")
@Getter
@NoArgsConstructor
public class ReceiveId {
    @Id
    private String id;
    private String videoId;
    private String watchdata;
    private LinkedHashMap<String, Object> snippet;
    private List<List<Object>> scoreList;
    private String visualization;
    private List<Integer> attentionList;

    @Builder
    public ReceiveId(String videoId, String watchdata, LinkedHashMap<String, Object> snippet, List<List<Object>> scoreList, String visualization, List<Integer> attentionList) {
        this.videoId = videoId;
        this.watchdata = watchdata;
        this.snippet = snippet;
        this.scoreList = scoreList;
        this.visualization = visualization;
        this.attentionList = attentionList;
    }
}
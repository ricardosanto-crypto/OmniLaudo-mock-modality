package com.omnilaudo.mockmodality.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DicomSendResponse {

    private String status;

    private String message;

    @JsonProperty("accession_number")
    private String accessionNumber;

    @JsonProperty("orthanc_study_id")
    private String orthancStudyId;

    @JsonProperty("timestamp")
    private long timestamp;

    @JsonProperty("success")
    private boolean success;
}

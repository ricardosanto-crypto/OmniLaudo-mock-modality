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
public class ExamSimulationRequest {

    @JsonProperty("accession_number")
    private String accessionNumber;

    @JsonProperty("patient_id")
    private Long patientId;

    @JsonProperty("patient_name")
    private String patientName;

    @JsonProperty("exam_type")
    private String examType;

    @JsonProperty("modality")
    private String modality;

    @JsonProperty("description")
    private String description;
}

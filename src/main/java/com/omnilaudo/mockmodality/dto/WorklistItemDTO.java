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
public class WorklistItemDTO {
    
    @JsonProperty("patient_name")
    private String patientName;
    
    @JsonProperty("patient_id")
    private String patientId;
    
    @JsonProperty("accession_number")
    private String accessionNumber;
    
    @JsonProperty("study_instance_uid")
    private String studyInstanceUID;
    
    @JsonProperty("modality")
    private String modality;
    
    @JsonProperty("study_description")
    private String studyDescription;
    
    @JsonProperty("scheduled_date")
    private String scheduledDate;
    
    @JsonProperty("scheduled_time")
    private String scheduledTime;

    @JsonProperty("referring_physician")
    private String referringPhysician;

    @JsonProperty("source")
    private String source;
}

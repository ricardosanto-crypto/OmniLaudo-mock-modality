package com.omnilaudo.mockmodality.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateImagesRequest {
    private String patientName;
    private String patientId;
    private String studyInstanceUID;
    private String accessionNumber;
    private String modality;
}

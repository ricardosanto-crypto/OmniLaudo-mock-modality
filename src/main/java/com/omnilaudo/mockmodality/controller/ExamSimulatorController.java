package com.omnilaudo.mockmodality.controller;

import com.omnilaudo.mockmodality.dto.DicomSendResponse;
import com.omnilaudo.mockmodality.dto.ExamSimulationRequest;
import com.omnilaudo.mockmodality.service.DicomGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Slf4j
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ExamSimulatorController {

    private final DicomGeneratorService dicomGeneratorService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        log.info("Health check called");
        return ResponseEntity.ok("Mock Modality is running ✅ - " + System.currentTimeMillis());
    }

    @GetMapping("/simulate")
    public ResponseEntity<List<DicomSendResponse>> simulateModality() {
        log.info("🎬 Iniciando simulação de modalidade...");
        List<DicomSendResponse> responses = dicomGeneratorService.simulateModality();
        return ResponseEntity.ok(responses);
    }

    @PostMapping(value = {"/api/v1/exams/upload", "/exams/upload"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DicomSendResponse> uploadExam(
            @RequestParam("accession_number") String accessionNumber,
            @RequestParam("patient_id") String patientId,
            @RequestParam("patient_name") String patientName,
            @RequestParam(value = "exam_type", required = false) String examType,
            @RequestParam(value = "modality", required = false) String modality,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("file") MultipartFile file) {
        
        log.info("📤 Upload de exame recebido: Accession={}, Paciente={}", accessionNumber, patientName);
        
        ExamSimulationRequest request = ExamSimulationRequest.builder()
                .accessionNumber(accessionNumber)
                .patientId(Long.parseLong(patientId))
                .patientName(patientName)
                .examType(examType)
                .modality(modality)
                .description(description)
                .build();

        try {
            byte[] dicomBytes = file.getBytes();
            DicomSendResponse response = dicomGeneratorService.uploadAndSendDicom(request, dicomBytes);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Erro ao processar upload: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(DicomSendResponse.builder()
                            .success(false)
                            .status("ERROR")
                            .message("Error: " + e.getMessage())
                            .accessionNumber(accessionNumber)
                            .build());
        }
    }
}

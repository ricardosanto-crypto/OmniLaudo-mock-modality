package com.omnilaudo.mockmodality.controller;

import com.omnilaudo.mockmodality.dto.DicomSendResponse;
import com.omnilaudo.mockmodality.dto.ExamSimulationRequest;
import com.omnilaudo.mockmodality.service.DicomGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/exams")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ExamSimulatorController {

    private final DicomGeneratorService dicomGeneratorService;

    /**
     * Simula a execução de um exame na modalidade mockada:
     * 1. Recebe Accession Number + dados do paciente
     * 2. Gera um arquivo DICOM mockado
     * 3. Envia para Orthanc
     * 4. O SincronizadorDicomService detecta a nova imagem
     * 5. Vincula ao agendamento no OmniLaudo
     */
    @PostMapping("/simulate")
    public ResponseEntity<DicomSendResponse> simulateExam(@RequestBody ExamSimulationRequest request) {
        log.info("📥 Requisição recebida para simular exame: {}", request.getAccessionNumber());

        if (request.getAccessionNumber() == null || request.getAccessionNumber().isBlank()) {
            return ResponseEntity.badRequest().body(
                DicomSendResponse.builder()
                    .success(false)
                    .status("INVALID_REQUEST")
                    .message("Accession number is required")
                    .timestamp(System.currentTimeMillis())
                    .build()
            );
        }

        DicomSendResponse response = dicomGeneratorService.simulateAndSendExam(request);
        
        HttpStatus status = response.isSuccess() ? HttpStatus.CREATED : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DicomSendResponse> uploadDicom(
            @RequestParam("accession_number") String accessionNumber,
            @RequestParam("patient_id") Long patientId,
            @RequestParam("patient_name") String patientName,
            @RequestParam(value = "exam_type", required = false) String examType,
            @RequestParam(value = "modality", required = false) String modality,
            @RequestParam(value = "description", required = false) String description,
            @RequestPart("file") MultipartFile dicomFile) {

        log.info("📥 Requisição de upload DICOM recebida: {}", accessionNumber);

        if (accessionNumber == null || accessionNumber.isBlank()) {
            return ResponseEntity.badRequest().body(
                DicomSendResponse.builder()
                    .success(false)
                    .status("INVALID_REQUEST")
                    .message("Accession number is required")
                    .timestamp(System.currentTimeMillis())
                    .build()
            );
        }

        if (dicomFile == null || dicomFile.isEmpty()) {
            return ResponseEntity.badRequest().body(
                DicomSendResponse.builder()
                    .success(false)
                    .status("INVALID_REQUEST")
                    .message("DICOM file is required")
                    .accessionNumber(accessionNumber)
                    .timestamp(System.currentTimeMillis())
                    .build()
            );
        }

        try {
            ExamSimulationRequest request = ExamSimulationRequest.builder()
                    .accessionNumber(accessionNumber)
                    .patientId(patientId)
                    .patientName(patientName)
                    .examType(examType)
                    .modality(modality)
                    .description(description)
                    .build();

            DicomSendResponse response = dicomGeneratorService.uploadAndSendDicom(request, dicomFile.getBytes());
            HttpStatus status = response.isSuccess() ? HttpStatus.CREATED : HttpStatus.INTERNAL_SERVER_ERROR;
            return ResponseEntity.status(status).body(response);
        } catch (Exception e) {
            log.error("Erro ao processar upload DICOM: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Failed to process DICOM upload: " + e.getMessage())
                    .accessionNumber(accessionNumber)
                    .timestamp(System.currentTimeMillis())
                    .build()
            );
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Mock Modality is running ✅");
    }

    /**
     * Info endpoint
     */
    @GetMapping("/info")
    public ResponseEntity<String> info() {
        return ResponseEntity.ok(
            "OmniLaudo Mock Modality v1.0\n" +
            "Ultrasound/Modality Simulator for Testing RIS/PACS Integration\n" +
            "Endpoints:\n" +
            "  POST /api/v1/exams/simulate - Simulate exam execution\n" +
            "  GET  /api/v1/exams/health - Health check\n" +
            "  GET  /api/v1/exams/info - This info"
        );
    }
}

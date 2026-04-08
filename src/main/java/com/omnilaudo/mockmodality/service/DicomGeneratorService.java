package com.omnilaudo.mockmodality.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnilaudo.mockmodality.dto.DicomSendResponse;
import com.omnilaudo.mockmodality.dto.ExamSimulationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DicomGeneratorService {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${modality.orthanc.url:http://orthanc:8042}")
    private String orthancUrl;

    @Value("${modality.orthanc.username:}")
    private String orthancUsername;

    @Value("${modality.orthanc.password:}")
    private String orthancPassword;

    /**
     * Gera um arquivo DICOM mockado com os dados fornecidos e o envia para Orthanc
     */
    public DicomSendResponse simulateAndSendExam(ExamSimulationRequest request) {
        try {
            log.info("🎬 Iniciando simulação de exame: Accession={}, Paciente={}", 
                request.getAccessionNumber(), request.getPatientName());

            // 1. Gera arquivo DICOM mockado
            byte[] dicomData = generateMockDicom(request);
            log.debug("✅ Arquivo DICOM gerado: {} bytes", dicomData.length);

            // 2. Envia para Orthanc via REST
            String orthancStudyId = sendToOrthanc(dicomData, request.getAccessionNumber());
            log.info("✅ DICOM enviado ao Orthanc! Study ID: {}", orthancStudyId);

            return DicomSendResponse.builder()
                    .success(true)
                    .status("SUCCESS")
                    .message("Exam simulated and sent to Orthanc successfully")
                    .accessionNumber(request.getAccessionNumber())
                    .orthancStudyId(orthancStudyId)
                    .timestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("❌ Erro ao simular exame: {}", e.getMessage(), e);
            return DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error: " + e.getMessage())
                    .accessionNumber(request.getAccessionNumber())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    public DicomSendResponse uploadAndSendDicom(ExamSimulationRequest request, byte[] dicomData) {
        try {
            log.info("🎬 Enviando DICOM real ao Orthanc: Accession={}, Paciente={}",
                    request.getAccessionNumber(), request.getPatientName());

            byte[] validatedDicom = ensureAccessionOnDicom(dicomData, request.getAccessionNumber());
            String orthancStudyId = sendToOrthanc(validatedDicom, request.getAccessionNumber());
            log.info("✅ DICOM real enviado ao Orthanc! Study ID: {}", orthancStudyId);

            return DicomSendResponse.builder()
                    .success(true)
                    .status("SUCCESS")
                    .message("Real DICOM uploaded and sent to Orthanc successfully")
                    .accessionNumber(request.getAccessionNumber())
                    .orthancStudyId(orthancStudyId)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("❌ Erro ao enviar DICOM real: {}", e.getMessage(), e);
            return DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error: " + e.getMessage())
                    .accessionNumber(request.getAccessionNumber())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    private byte[] ensureAccessionOnDicom(byte[] dicomData, String accessionNumber) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(dicomData);
             DicomInputStream dis = new DicomInputStream(bais)) {

            Attributes fileMetaInformation = dis.getFileMetaInformation();
            Attributes dataset = dis.readDataset(-1, -1);
            String existingAccession = dataset.getString(Tag.AccessionNumber);

            if (existingAccession == null || !existingAccession.equals(accessionNumber)) {
                log.info("Ajustando AccessionNumber de '{}' para '{}' no DICOM enviado.", existingAccession, accessionNumber);
                dataset.setString(Tag.AccessionNumber, VR.SH, accessionNumber);

                try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                     DicomOutputStream dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)) {
                    if (fileMetaInformation != null) {
                        dos.writeFileMetaInformation(fileMetaInformation);
                    }
                    dos.writeDataset(fileMetaInformation, dataset);
                    return baos.toByteArray();
                }
            }

            return dicomData;
        }
    }

    /**
     * Gera um arquivo DICOM mockado Em memória com dados realistas
     */
    private byte[] generateMockDicom(ExamSimulationRequest request) throws Exception {
        // Cria um novo dataset DICOM
        Attributes dcmAttrs = new Attributes();

        // Meta information
        dcmAttrs.setString(Tag.PatientName, VR.PN, request.getPatientName());
        dcmAttrs.setString(Tag.PatientID, VR.LO, String.valueOf(request.getPatientId()));
        dcmAttrs.setString(Tag.AccessionNumber, VR.SH, request.getAccessionNumber());

        // Study Information
        String studyUID = generateUID();
        String seriesUID = generateUID();
        String instanceUID = generateUID();

        dcmAttrs.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
        dcmAttrs.setString(Tag.SeriesInstanceUID, VR.UI, seriesUID);
        dcmAttrs.setString(Tag.SOPInstanceUID, VR.UI, instanceUID);
        dcmAttrs.setString(Tag.SOPClassUID, VR.UI, UID.UltrasoundImageStorage);

        // Date and Time
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String nowTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        dcmAttrs.setString(Tag.StudyDate, VR.DA, now);
        dcmAttrs.setString(Tag.StudyTime, VR.TM, nowTime);
        dcmAttrs.setString(Tag.ContentDate, VR.DA, now);
        dcmAttrs.setString(Tag.ContentTime, VR.TM, nowTime);

        // Modality
        String modality = request.getModality() != null ? request.getModality() : "US";
        dcmAttrs.setString(Tag.Modality, VR.CS, modality);

        // Description
        if (request.getDescription() != null) {
            dcmAttrs.setString(Tag.StudyDescription, VR.LO, request.getDescription());
            dcmAttrs.setString(Tag.SeriesDescription, VR.LO, request.getDescription());
        }

        // Manufacturer and Device Info
        dcmAttrs.setString(Tag.Manufacturer, VR.LO, "OmniLaudo Mock Modality");
        dcmAttrs.setString(Tag.ManufacturerModelName, VR.LO, "MockUS-Simulator-v1.0");
        dcmAttrs.setString(Tag.StationName, VR.SH, "MockModality-01");

        // Image dimensions (mock data)
        dcmAttrs.setInt(Tag.SamplesPerPixel, VR.US, 1);
        dcmAttrs.setInt(Tag.Rows, VR.US, 512);
        dcmAttrs.setInt(Tag.Columns, VR.US, 512);
        dcmAttrs.setInt(Tag.BitsAllocated, VR.US, 8);
        dcmAttrs.setInt(Tag.BitsStored, VR.US, 8);
        dcmAttrs.setInt(Tag.HighBit, VR.US, 7);
        dcmAttrs.setString(Tag.PixelRepresentation, VR.US, "0");
        dcmAttrs.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");

        // Gera dados de pixel mockados (simples para teste)
        byte[] pixelData = generateMockPixelData(512, 512);
        dcmAttrs.setBytes(Tag.PixelData, VR.OB, pixelData);

        // Serializa o dataset DICOM para bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DicomOutputStream dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)) {
            dos.writeDataset(null, dcmAttrs);
        }

        return baos.toByteArray();
    }

    /**
     * Gera dados de pixel mockados (gradient simples)
     */
    private byte[] generateMockPixelData(int rows, int cols) {
        byte[] pixelData = new byte[rows * cols];
        for (int i = 0; i < rows * cols; i++) {
            pixelData[i] = (byte) (i % 256);
        }
        return pixelData;
    }

    /**
     * Gera um UID único seguindo o padrão DICOM
     */
    private String generateUID() {
        return "1.2.826.0.1.3680043.8.498." + System.currentTimeMillis() + "." + new Random().nextInt(10000);
    }

    /**
     * Envia o arquivo DICOM para Orthanc via REST API
     */
    private String sendToOrthanc(byte[] dicomData, String accessionNumber) throws Exception {
        log.debug("📤 Enviando DICOM para Orthanc...");

        var webClient = webClientBuilder.build();

        // Constrói a requisição POST para upload de DICOM
        var response = webClient.post()
                .uri(orthancUrl + "/instances")
                .bodyValue(dicomData)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Erro ao enviar DICOM ao Orthanc: {}", e.getMessage()))
                .block();

        if (response != null && response.containsKey("ID")) {
            String instanceId = (String) response.get("ID");
            log.debug("Instance criada no Orthanc com ID: {}", instanceId);

            // Busca informações do estudo
            Map<String, Object> instanceInfo = webClient.get()
                    .uri(orthancUrl + "/instances/" + instanceId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (instanceInfo != null && instanceInfo.containsKey("ParentStudy")) {
                return (String) instanceInfo.get("ParentStudy");
            }
        }

        throw new Exception("Falha ao enviar DICOM para Orthanc");
    }
}

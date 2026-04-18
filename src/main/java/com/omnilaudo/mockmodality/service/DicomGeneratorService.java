package com.omnilaudo.mockmodality.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnilaudo.mockmodality.dto.DicomSendResponse;
import com.omnilaudo.mockmodality.dto.ExamSimulationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class DicomGeneratorService {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Autowired
    public DicomGeneratorService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * Classe para armazenar DICOM temporariamente
     */
    private static class StoredDicom {
        private final ExamSimulationRequest request;
        private final byte[] dicomData;
        private final long timestamp;

        public StoredDicom(ExamSimulationRequest request, byte[] dicomData) {
            this.request = request;
            this.dicomData = dicomData;
            this.timestamp = System.currentTimeMillis();
        }

        public ExamSimulationRequest getRequest() { return request; }
        public byte[] getDicomData() { return dicomData; }
        public long getTimestamp() { return timestamp; }
    }

    // Armazenamento temporário dos DICOMs até o finalizar
    private final Map<String, StoredDicom> tempDicomStorage = new ConcurrentHashMap<>();

    @Value("${modality.orthanc.url:http://orthanc:8042}")
    private String orthancUrl;

    @Value("${modality.orthanc.username:}")
    private String orthancUsername;

    @Value("${modality.orthanc.password:}")
    private String orthancPassword;

    @Value("${modality.dicom.host:orthanc}")
    private String dicomHost;

    @Value("${modality.dicom.port:4242}")
    private int dicomPort;

    @Value("${modality.aet:MOCK_MODALITY}")
    private String modalityAET;

    @Value("${modality.orthanc.aet:ORTHANC}")
    private String orthancAET;

    @Value("${modality.ris.url:http://localhost:3001/api/v1/dicom/worklist}")
    private String risUrl;

    @Value("${modality.ris.token:}")
    private String risToken;

    /**
     * Simula um equipamento de modalidade: consulta worklist, gera 3 DICOMs e envia via C-STORE
     * Se o worklist falhar, usa dados mockados como fallback
     */
    public List<DicomSendResponse> simulateModality() {
        List<DicomSendResponse> responses = new ArrayList<>();
        try {
            log.info("🎬 ===== CONFIGURACAO DICOM =====");
            log.info("🎬 DICOM Host: {}:{}", dicomHost, dicomPort);
            log.info("🎬 AET: {} -> {}", modalityAET, orthancAET);
            log.info("🎬 Orthanc URL: {}", orthancUrl);
            log.info("🎬 RIS URL: {}", risUrl);
            log.info("🎬 ==============================");
            log.info("🔍 Iniciando simulação de modalidade: consultando worklist...");

            // 1. Tentar worklist DICOM primeiro, depois RIS, depois fallback mockado
            List<Attributes> worklistItems;
            Exception firstError = null;

            // Tentativa 1: C-FIND DICOM via Orthanc
            try {
                worklistItems = queryWorklist();
                log.info("📋 Worklist DICOM retornou {} itens", worklistItems.size());
            } catch (Exception e) {
                log.warn("⚠️ Falha no worklist DICOM: {}. Tentando RIS...", e.getMessage());
                firstError = e;
                worklistItems = null;
            }

            // Tentativa 2: RIS API
            if (worklistItems == null || worklistItems.isEmpty()) {
                try {
                    worklistItems = queryWorklistFromRis();
                    if (worklistItems != null && !worklistItems.isEmpty()) {
                        log.info("📋 RIS retornou {} itens", worklistItems.size());
                    }
                } catch (Exception e) {
                    log.warn("⚠️ Falha ao consultar RIS: {}.", e.getMessage());
                }
            }

            // Fallback: dados mockados
            if (worklistItems == null || worklistItems.isEmpty()) {
                log.warn("⚠️ Nenhuma fonte de worklist disponível. Usando dados mockados como fallback.");
                worklistItems = generateMockWorklistData();
                log.info("📋 Usando {} itens mockados", worklistItems.size());
            }

            for (Attributes worklistItem : worklistItems) {
                try {
                    // Extrair dados do worklist
                    String accessionNumber = worklistItem.getString(Tag.AccessionNumber);
                    String patientName = worklistItem.getString(Tag.PatientName);
                    String patientID = worklistItem.getString(Tag.PatientID);
                    String studyInstanceUID = worklistItem.getString(Tag.StudyInstanceUID);
                    String modality = worklistItem.getString(Tag.Modality) != null ? worklistItem.getString(Tag.Modality) : "US";

                    log.info("🎬 Processando exame: Accession={}, Paciente={}, Modality={}", accessionNumber, patientName, modality);

                    // 2. Gerar 3 DICOMs para este exame
                    List<byte[]> dicomImages = generateExamImages(accessionNumber, patientName, patientID, studyInstanceUID, modality);

                    // 3. Enviar cada imagem via C-STORE ou REST (fallback)
                    for (int i = 0; i < dicomImages.size(); i++) {
                        DicomSendResponse response = sendDicomViaCStoreWithFallback(dicomImages.get(i), accessionNumber + "_img" + (i+1));
                        responses.add(response);
                    }

                } catch (Exception e) {
                    log.error("❌ Erro ao processar item do worklist: {}", e.getMessage());
                    responses.add(DicomSendResponse.builder()
                            .success(false)
                            .status("ERROR")
                            .message("Error processing worklist item: " + e.getMessage())
                            .timestamp(System.currentTimeMillis())
                            .build());
                }
            }

        } catch (Exception e) {
            log.error("❌ Erro na simulação de modalidade: {}", e.getMessage(), e);
            responses.add(DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error in modality simulation: " + e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        return responses;
    }

    /**
     * Gera dados mockados para teste quando worklist não está disponível
     */
    private List<Attributes> generateMockWorklistData() {
        List<Attributes> mockItems = new ArrayList<>();

        // Gerar 2 exames mockados
        for (int j = 1; j <= 2; j++) {
            Attributes mockItem = new Attributes();
            mockItem.setString(Tag.AccessionNumber, VR.SH, "MOCK-ACC-" + System.currentTimeMillis() + "-" + j);
            mockItem.setString(Tag.PatientName, VR.PN, "Paciente Mock " + j);
            mockItem.setString(Tag.PatientID, VR.LO, String.valueOf(1000 + j));
            mockItem.setString(Tag.StudyInstanceUID, VR.UI, generateUID());
            mockItem.setString(Tag.Modality, VR.CS, j % 2 == 0 ? "MR" : "US");
            mockItems.add(mockItem);
        }

        return mockItems;
    }

    private List<Attributes> queryWorklist() throws Exception {
        List<Attributes> results = new ArrayList<>();

        Device device = new Device("mock-modality");
        device.setExecutor(Executors.newCachedThreadPool());
        device.setScheduledExecutor(Executors.newSingleThreadScheduledExecutor());

        Connection conn = new Connection();
        conn.setHostname(dicomHost);
        conn.setPort(dicomPort);
        device.addConnection(conn);

        ApplicationEntity ae = new ApplicationEntity(modalityAET);
        ae.addConnection(conn);
        device.addApplicationEntity(ae);

        AAssociateRQ rq = new AAssociateRQ();
        rq.setCallingAET(modalityAET);
        rq.setCalledAET(orthancAET);
        rq.addPresentationContext(
                new PresentationContext(1, UID.ModalityWorklistInformationModelFind, UID.ExplicitVRLittleEndian));
        rq.addPresentationContext(
                new PresentationContext(3, UID.ModalityWorklistInformationModelFind, UID.ImplicitVRLittleEndian));

        Association as = ae.connect(conn, rq);
        try {
            Attributes query = new Attributes();
            query.setString(Tag.QueryRetrieveLevel, VR.CS, "WORKLIST");

            try {
                DimseRSP rsp = as.cfind(UID.ModalityWorklistInformationModelFind, 1, query, null, 0);
                Attributes command = rsp.getCommand();

                while (rsp.next()) {
                    Attributes dataset = rsp.getDataset();
                    log.debug("C-FIND next called: datasetPresent={}", dataset != null);
                    if (dataset != null) {
                        results.add(dataset);
                    }
                }

                int status = -1;
                if (command != null) {
                    status = command.getInt(Tag.Status, -1);
                } else {
                    log.warn("⚠️ C-FIND command é null - possivelmente abortado pelo servidor");
                }
                log.debug("C-FIND command status = {}", status);

            } catch (Exception e) {
                log.error("❌ Erro no C-FIND MWL: {}", e.getMessage(), e);
                throw e;
            }
        } finally {
            as.release();
        }

        return results;
    }

private List<Attributes> queryWorklistFromRis() {
    List<Attributes> results = new ArrayList<>();
    
    try {
        log.info("🔍 Consultando worklist do RIS: {}", risUrl);
        
        var request = webClientBuilder.build()
            .get()
            .uri(risUrl);
        
        if (risToken != null && !risToken.isEmpty()) {
            request = request.headers(headers -> headers.setBearerAuth(risToken));
        }
        
        Map<String, Object> response = request
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
            .block();
        
        if (response == null || !response.containsKey("data")) {
            log.warn("⚠️ Resposta inválida do RIS");
            return results;
        }
        
        Object data = response.get("data");
        if (!(data instanceof List)) {
            log.warn("⚠️ Formato de dados inválido do RIS");
            return results;
        }
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> risWorklist = (List<Map<String, Object>>) data;
        
        if (risWorklist.isEmpty()) {
            log.warn("⚠️ Worklist vazio do RIS");
            return results;
        }
        
        log.info("📋 RIS retornou {} itens", risWorklist.size());
        
        for (Map<String, Object> item : risWorklist) {
            Attributes attrs = new Attributes();
            
            String accessionNumber = (String) item.get("accessionNumber");
            String patientName = (String) item.get("patientName");
            Object patientIdObj = item.get("patientId");
            String patientId = patientIdObj != null ? patientIdObj.toString() : null;
            String modality = (String) item.get("modality");
            String studyDescription = (String) item.getOrDefault("studyDescription", (String) item.get("procedureDescription"));
            
            if (accessionNumber != null) {
                attrs.setString(Tag.AccessionNumber, VR.SH, accessionNumber);
            }
            if (patientName != null) {
                attrs.setString(Tag.PatientName, VR.PN, patientName);
            }
            if (patientId != null) {
                attrs.setString(Tag.PatientID, VR.LO, patientId);
            }
            if (modality != null) {
                attrs.setString(Tag.Modality, VR.CS, modality);
            } else {
                attrs.setString(Tag.Modality, VR.CS, "US");
            }
            if (studyDescription != null) {
                attrs.setString(Tag.StudyDescription, VR.LO, studyDescription);
            }
            
            attrs.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
            attrs.setString(Tag.StudyInstanceUID, VR.UI, generateUID());
            
            results.add(attrs);
            log.debug("✅ Item convertido: Accession={}, Paciente={}", accessionNumber, patientName);
        }
        
    } catch (Exception e) {
        log.error("❌ Erro ao consultar worklist do RIS: {}", e.getMessage());
    }
    
    return results;
}

    private List<byte[]> generateExamImages(String accessionNumber, String patientName, String patientID, String studyInstanceUID, String modality) throws Exception {
        List<byte[]> images = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            Attributes dcmAttrs = new Attributes();

            // Meta information
            dcmAttrs.setString(Tag.PatientName, VR.PN, patientName);
            dcmAttrs.setString(Tag.PatientID, VR.LO, patientID);
            dcmAttrs.setString(Tag.AccessionNumber, VR.SH, accessionNumber);

            // Study Information
            dcmAttrs.setString(Tag.StudyInstanceUID, VR.UI, studyInstanceUID);
            String seriesUID = generateUID();
            String instanceUID = generateUID();

            dcmAttrs.setString(Tag.SeriesInstanceUID, VR.UI, seriesUID);
            dcmAttrs.setString(Tag.SOPInstanceUID, VR.UI, instanceUID);
            dcmAttrs.setString(Tag.SOPClassUID, VR.UI, modality.equals("MR") ? UID.MRImageStorage : UID.UltrasoundImageStorage);

            // Date and Time
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String nowTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
            dcmAttrs.setString(Tag.StudyDate, VR.DA, now);
            dcmAttrs.setString(Tag.StudyTime, VR.TM, nowTime);
            dcmAttrs.setString(Tag.ContentDate, VR.DA, now);
            dcmAttrs.setString(Tag.ContentTime, VR.TM, nowTime);

            // Modality
            dcmAttrs.setString(Tag.Modality, VR.CS, modality);

            // Series Number
            dcmAttrs.setInt(Tag.SeriesNumber, VR.IS, 1);
            dcmAttrs.setInt(Tag.InstanceNumber, VR.IS, i);

            // Manufacturer and Device Info
            dcmAttrs.setString(Tag.Manufacturer, VR.LO, "OmniLaudo Mock Modality");
            dcmAttrs.setString(Tag.ManufacturerModelName, VR.LO, modality.equals("MR") ? "MockMR-Simulator-v1.0" : "MockUS-Simulator-v1.0");
            dcmAttrs.setString(Tag.StationName, VR.SH, "MockModality-01");

            // Image dimensions
            dcmAttrs.setInt(Tag.SamplesPerPixel, VR.US, 1);
            dcmAttrs.setInt(Tag.Rows, VR.US, 512);
            dcmAttrs.setInt(Tag.Columns, VR.US, 512);
            dcmAttrs.setInt(Tag.BitsAllocated, VR.US, 8);
            dcmAttrs.setInt(Tag.BitsStored, VR.US, 8);
            dcmAttrs.setInt(Tag.HighBit, VR.US, 7);
            dcmAttrs.setString(Tag.PixelRepresentation, VR.US, "0");
            dcmAttrs.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");

            // Pixel data
            byte[] pixelData = generateMockPixelData(512, 512, i);
            dcmAttrs.setBytes(Tag.PixelData, VR.OB, pixelData);

            // Serialize
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DicomOutputStream dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)) {
                dos.writeDataset(null, dcmAttrs);
            }
            images.add(baos.toByteArray());
        }

        return images;
    }
    private DicomSendResponse sendDicomViaCStore(byte[] dicomData, String identifier) {
        try {
            log.info("🔌 Tentando C-STORE para {}:{}", dicomHost, dicomPort);
            
            Device device = new Device("mock-modality");
            Connection conn = new Connection();
            conn.setHostname(dicomHost);
            conn.setPort(dicomPort);
            device.addConnection(conn);

            ApplicationEntity ae = new ApplicationEntity(modalityAET);
            ae.addConnection(conn);
            device.addApplicationEntity(ae);

            AAssociateRQ rq = new AAssociateRQ();
            rq.setCallingAET(modalityAET);
            rq.setCalledAET(orthancAET);
            rq.addPresentationContext(new PresentationContext(1, UID.UltrasoundImageStorage, UID.ImplicitVRLittleEndian));
            rq.addPresentationContext(new PresentationContext(3, UID.MRImageStorage, UID.ImplicitVRLittleEndian));

            Association as = ae.connect(conn, rq);
            try {
                Attributes fmi;
                Attributes dataset;
                try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(dicomData))) {
                    fmi = dis.getFileMetaInformation();
                    dataset = dis.readDataset(-1, -1);
                }

                String cuid = fmi.getString(Tag.MediaStorageSOPClassUID);
                String iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID);
                String tsuid = fmi.getString(Tag.TransferSyntaxUID);

                DimseRSP rsp = as.cstore(cuid, iuid, 1, new DataWriterAdapter(dataset), null);
                rsp.next();

                log.info("✅ DICOM enviado via C-STORE: {}", identifier);

                return DicomSendResponse.builder()
                        .success(true)
                        .status("SUCCESS")
                        .message("DICOM sent via C-STORE successfully")
                        .accessionNumber(identifier)
                        .timestamp(System.currentTimeMillis())
                        .build();

            } finally {
                as.release();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao enviar DICOM via C-STORE: {}", e.getMessage());
            return DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error sending DICOM via C-STORE: " + e.getMessage())
                    .accessionNumber(identifier)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * Tenta enviar via C-STORE, se falhar faz fallback para REST API (Orthanc)
     */
    private DicomSendResponse sendDicomViaCStoreWithFallback(byte[] dicomData, String identifier) {
        try {
            // Tenta C-STORE primeiro
            log.debug("Tentando enviar DICOM via C-STORE: {}", identifier);
            DicomSendResponse cstoreResponse = sendDicomViaCStore(dicomData, identifier);
            if (cstoreResponse.isSuccess()) {
                return cstoreResponse;
            }
            
            // Se C-STORE falhar, tenta REST
            log.warn("C-STORE falhou ({}), tentando via REST API...", cstoreResponse.getMessage());
            return sendToOrthancRest(dicomData, identifier);
            
        } catch (Exception e) {
            log.error("❌ Erro ao enviar DICOM com fallback: {}", e.getMessage());
            return DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error sending DICOM: " + e.getMessage())
                    .accessionNumber(identifier)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * Envia DICOM para Orthanc via REST API
     */
    private DicomSendResponse sendToOrthancRest(byte[] dicomData, String identifier) {
        try {
            log.info("📤 Enviando DICOM via REST para Orthanc: {}", identifier);
            String orthancStudyId = sendToOrthanc(dicomData, identifier);
            
            return DicomSendResponse.builder()
                    .success(true)
                    .status("SUCCESS")
                    .message("DICOM sent via REST API successfully")
                    .accessionNumber(identifier)
                    .orthancStudyId(orthancStudyId)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("❌ Erro ao enviar DICOM via REST: {}", e.getMessage());
            return DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error sending DICOM via REST: " + e.getMessage())
                    .accessionNumber(identifier)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

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

    /**
     * Armazena o DICOM temporariamente (upload realizado)
     */
    public DicomSendResponse storeDicomTemporarily(ExamSimulationRequest request, byte[] dicomData) {
        try {
            log.info("💾 Armazenando DICOM temporariamente: Accession={}", request.getAccessionNumber());

            byte[] validatedDicom = ensureAccessionOnDicom(dicomData, request.getAccessionNumber());
            tempDicomStorage.put(request.getAccessionNumber(), new StoredDicom(request, validatedDicom));

            return DicomSendResponse.builder()
                    .success(true)
                    .status("STORED")
                    .message("DICOM stored temporarily, ready for finalization")
                    .accessionNumber(request.getAccessionNumber())
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("❌ Erro ao armazenar DICOM temporariamente: {}", e.getMessage(), e);
            return DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error storing DICOM: " + e.getMessage())
                    .accessionNumber(request.getAccessionNumber())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * Finaliza o exame: envia o DICOM armazenado para o Orthanc
     */
    public DicomSendResponse finalizarExame(String accessionNumber) {
        try {
            log.info("🎯 Finalizando exame: enviando DICOM para Orthanc: {}", accessionNumber);

            StoredDicom stored = tempDicomStorage.remove(accessionNumber);
            if (stored == null) {
                return DicomSendResponse.builder()
                        .success(false)
                        .status("NOT_FOUND")
                        .message("No DICOM found for accession number: " + accessionNumber)
                        .accessionNumber(accessionNumber)
                        .timestamp(System.currentTimeMillis())
                        .build();
            }

            String orthancStudyId = sendToOrthanc(stored.getDicomData(), accessionNumber);
            log.info("✅ DICOM enviado ao Orthanc no finalizar! Study ID: {}", orthancStudyId);

            return DicomSendResponse.builder()
                    .success(true)
                    .status("FINALIZED")
                    .message("Exam finalized and DICOM sent to Orthanc successfully")
                    .accessionNumber(accessionNumber)
                    .orthancStudyId(orthancStudyId)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("❌ Erro ao finalizar exame: {}", e.getMessage(), e);
            return DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error finalizing exam: " + e.getMessage())
                    .accessionNumber(accessionNumber)
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
        byte[] pixelData = generateMockPixelData(512, 512, 0);
        dcmAttrs.setBytes(Tag.PixelData, VR.OB, pixelData);

        // Serializa o dataset DICOM para bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DicomOutputStream dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)) {
            dos.writeDataset(null, dcmAttrs);
        }

        return baos.toByteArray();
    }

    /**
     * Gera dados de pixel mockados (gradient simples variado por imagem)
     */
    private byte[] generateMockPixelData(int rows, int cols, int imageIndex) {
        byte[] pixelData = new byte[rows * cols];
        for (int i = 0; i < rows * cols; i++) {
            pixelData[i] = (byte) ((i + imageIndex * 50) % 256);
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
        log.debug("📤 Enviando DICOM para Orthanc... URL: {}, Size: {}", orthancUrl, dicomData.length);

        var webClient = webClientBuilder.build();

        // Constrói a requisição POST para upload de DICOM
        var requestSpec = webClient.post()
                .uri(orthancUrl + "/instances");

        // Adiciona autenticação se credenciais estiverem configuradas
        if (orthancUsername != null && !orthancUsername.isEmpty() &&
            orthancPassword != null && !orthancPassword.isEmpty()) {
            log.debug("🔐 Usando autenticação: {}/{}", orthancUsername, "****");
            requestSpec = requestSpec.headers(headers -> headers.setBasicAuth(orthancUsername, orthancPassword));
        } else {
            log.debug("⚠️  Sem autenticação configurada");
        }

        log.debug("🌐 Fazendo requisição POST para {}/instances", orthancUrl);
        var response = requestSpec
                .bodyValue(dicomData)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("❌ Erro ao enviar DICOM ao Orthanc: {} - URL: {}", e.getMessage(), orthancUrl))
                .doOnSuccess(r -> log.debug("✅ Resposta recebida do Orthanc: {}", r))
                .block();

        if (response != null && response.containsKey("ID")) {
            String instanceId = (String) response.get("ID");
            log.debug("✅ Instance criada no Orthanc com ID: {}", instanceId);

            String parentStudy = (String) response.get("ParentStudy");
            if (parentStudy != null) {
                log.debug("✅ ParentStudy obtido: {}", parentStudy);
                return parentStudy;
            }

            log.warn("⚠️ ParentStudy não encontrado na resposta, buscando via API...");
            var studyRequestSpec = webClient.get()
                    .uri(orthancUrl + "/instances/" + instanceId);

            if (orthancUsername != null && !orthancUsername.isEmpty() &&
                orthancPassword != null && !orthancPassword.isEmpty()) {
                studyRequestSpec = studyRequestSpec.headers(headers -> headers.setBasicAuth(orthancUsername, orthancPassword));
            }

            Map<String, Object> instanceInfo = studyRequestSpec
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (instanceInfo != null && instanceInfo.containsKey("ParentStudy")) {
                return (String) instanceInfo.get("ParentStudy");
            }
        }

        throw new Exception("Falha ao enviar DICOM para Orthanc - resposta: " + response);
    }
}

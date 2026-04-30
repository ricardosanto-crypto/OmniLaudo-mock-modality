package com.omnilaudo.mockmodality.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnilaudo.mockmodality.dto.DicomSendResponse;
import com.omnilaudo.mockmodality.dto.ExamSimulationRequest;
import com.omnilaudo.mockmodality.dto.GenerateImagesRequest;
import com.omnilaudo.mockmodality.dto.WorklistItemDTO;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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
     * Retorna a lista de exames (worklist) consultando o Orthanc via C-FIND.
     */
    public List<WorklistItemDTO> getWorklist() {
        log.info("🔍 Consultando worklist para o frontend...");
        try {
            List<Attributes> items = queryWorklist();
            return items.stream().map(this::mapToWorklistItem).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("❌ Erro ao consultar worklist: {}", e.getMessage());
            // Fallback para dados mockados se falhar
            return generateMockWorklistData().stream().map(this::mapToWorklistItem).collect(Collectors.toList());
        }
    }

    private WorklistItemDTO mapToWorklistItem(Attributes attrs) {
        // MWL dados muitas vezes ficam dentro da sequência ScheduledProcedureStepSequence
        Attributes sps = attrs.getNestedDataset(Tag.ScheduledProcedureStepSequence);
        
        String modality = attrs.getString(Tag.Modality);
        if (modality == null && sps != null) modality = sps.getString(Tag.Modality);
        
        String date = attrs.getString(Tag.ScheduledProcedureStepStartDate);
        if (date == null && sps != null) date = sps.getString(Tag.ScheduledProcedureStepStartDate);
        
        String time = attrs.getString(Tag.ScheduledProcedureStepStartTime);
        if (time == null && sps != null) time = sps.getString(Tag.ScheduledProcedureStepStartTime);

        String description = attrs.getString(Tag.RequestedProcedureDescription);
        if (description == null && sps != null) description = sps.getString(Tag.ScheduledProcedureStepDescription);
        if (description == null) description = attrs.getString(Tag.StudyDescription);

        return WorklistItemDTO.builder()
                .patientName(attrs.getString(Tag.PatientName))
                .patientId(attrs.getString(Tag.PatientID))
                .accessionNumber(attrs.getString(Tag.AccessionNumber))
                .studyInstanceUID(attrs.getString(Tag.StudyInstanceUID))
                .modality(modality != null ? modality : "US")
                .studyDescription(description)
                .scheduledDate(date)
                .scheduledTime(time)
                .referringPhysician(attrs.getString(Tag.ReferringPhysicianName))
                .source("Orthanc (C-FIND)")
                .build();
    }

    /**
     * Gera e envia 2 imagens DICOM para o Orthanc baseado no exame selecionado.
     */
    public List<DicomSendResponse> generateAndSendImages(GenerateImagesRequest request) {
        log.info("🎬 Gerando e enviando imagens para: Paciente={}, Accession={}", 
                request.getPatientName(), request.getAccessionNumber());
        
        List<DicomSendResponse> responses = new ArrayList<>();
        String modality = request.getModality() != null ? request.getModality() : "US";

        try {
            // Gerar 2 imagens conforme solicitado
            List<byte[]> images = generateImages(request.getAccessionNumber(), 
                                               request.getPatientName(), 
                                               request.getPatientId(), 
                                               request.getStudyInstanceUID(), 
                                               modality, 
                                               2);

            for (int i = 0; i < images.size(); i++) {
                DicomSendResponse response = sendDicomViaCStoreWithFallback(images.get(i), 
                        request.getAccessionNumber() + "_img" + (i + 1));
                responses.add(response);
            }
        } catch (Exception e) {
            log.error("❌ Erro ao gerar/enviar imagens: {}", e.getMessage());
            responses.add(DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error: " + e.getMessage())
                    .accessionNumber(request.getAccessionNumber())
                    .timestamp(System.currentTimeMillis())
                    .build());
        }
        return responses;
    }

    private List<byte[]> generateImages(String accessionNumber, String patientName, String patientID, String studyInstanceUID, String modality, int count) throws Exception {
        List<byte[]> images = new ArrayList<>();
        File templateFile = pickRandomTemplateFile();

        for (int i = 1; i <= count; i++) {
            if (templateFile != null && templateFile.exists()) {
                images.add(patchTemplate(templateFile, accessionNumber, patientName, patientID, studyInstanceUID, modality, i));
            } else {
                images.add(generateSyntheticImage(accessionNumber, patientName, patientID, studyInstanceUID, modality, i));
            }
        }
        return images;
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

    @Value("${modality.templates.path:/app/templates}")
    private String templatesPath;

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
            
            // Tentativa 1: C-FIND DICOM via Orthanc
            try {
                worklistItems = queryWorklist();
                log.info("📋 Worklist DICOM retornou {} itens", worklistItems.size());
            } catch (Exception e) {
                log.warn("⚠️ Falha no worklist DICOM: {}. Tentando RIS...", e.getMessage());
                worklistItems = null;
            }

            // Tentativa 2: RIS API
            if (worklistItems == null || worklistItems.isEmpty()) {
                try {
                    worklistItems = queryWorklistFromRis();
                } catch (Exception e) {
                    log.warn("⚠️ Falha ao consultar RIS: {}.", e.getMessage());
                }
            }

            // Fallback: dados mockados
            if (worklistItems == null || worklistItems.isEmpty()) {
                log.warn("⚠️ Nenhuma fonte de worklist disponível. Usando dados mockados como fallback.");
                worklistItems = generateMockWorklistData();
            }

            for (Attributes worklistItem : worklistItems) {
                try {
                    String accessionNumber = worklistItem.getString(Tag.AccessionNumber);
                    String patientName = worklistItem.getString(Tag.PatientName);
                    String patientID = worklistItem.getString(Tag.PatientID);
                    String studyInstanceUID = worklistItem.getString(Tag.StudyInstanceUID);
                    String modality = worklistItem.getString(Tag.Modality) != null ? worklistItem.getString(Tag.Modality) : "US";

                    log.info("🎬 Processando exame: Accession={}, Paciente={}, Modality={}", accessionNumber, patientName, modality);

                    List<byte[]> dicomImages = generateExamImages(accessionNumber, patientName, patientID, studyInstanceUID, modality);

                    for (int i = 0; i < dicomImages.size(); i++) {
                        DicomSendResponse response = sendDicomViaCStoreWithFallback(dicomImages.get(i), accessionNumber + "_img" + (i+1));
                        responses.add(response);
                    }
                } catch (Exception e) {
                    log.error("❌ Erro ao processar item do worklist: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("❌ Erro na simulação de modalidade: {}", e.getMessage(), e);
        }
        return responses;
    }

    private List<Attributes> generateMockWorklistData() {
        List<Attributes> mockItems = new ArrayList<>();
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
            query.setString(Tag.PatientName, VR.PN, "*");
            query.setNull(Tag.PatientID, VR.LO);
            query.setNull(Tag.AccessionNumber, VR.SH);
            query.setNull(Tag.StudyInstanceUID, VR.UI);
            query.setNull(Tag.RequestedProcedureDescription, VR.LO);
            
            Attributes sps = new Attributes();
            sps.setNull(Tag.Modality, VR.CS);
            sps.setNull(Tag.ScheduledStationAETitle, VR.AE);
            sps.setNull(Tag.ScheduledProcedureStepStartDate, VR.DA);
            sps.setNull(Tag.ScheduledProcedureStepStartTime, VR.TM);
            sps.setNull(Tag.ScheduledProcedureStepDescription, VR.LO);
            query.newSequence(Tag.ScheduledProcedureStepSequence, 1).add(sps);

            try {
                DimseRSP rsp = as.cfind(UID.ModalityWorklistInformationModelFind, 0, query, null, 0);
                while (rsp.next()) {
                    Attributes dataset = rsp.getDataset();
                    if (dataset != null) results.add(dataset);
                }
            } catch (Exception e) {
                log.error("❌ Erro no C-FIND MWL: {}", e.getMessage());
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
            var request = webClientBuilder.build().get().uri(risUrl);
            if (risToken != null && !risToken.isEmpty()) {
                request = request.headers(headers -> headers.setBearerAuth(risToken));
            }
            
            Map<String, Object> response = request.retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
            
            if (response != null && response.containsKey("data")) {
                Object data = response.get("data");
                if (data instanceof List) {
                    List<Map<String, Object>> risWorklist = (List<Map<String, Object>>) data;
                    for (Map<String, Object> item : risWorklist) {
                        Attributes attrs = new Attributes();
                        attrs.setString(Tag.AccessionNumber, VR.SH, (String) item.get("accessionNumber"));
                        attrs.setString(Tag.PatientName, VR.PN, (String) item.get("patientName"));
                        attrs.setString(Tag.PatientID, VR.LO, item.get("patientId") != null ? item.get("patientId").toString() : "");
                        attrs.setString(Tag.Modality, VR.CS, (String) item.getOrDefault("modality", "US"));
                        attrs.setString(Tag.StudyInstanceUID, VR.UI, generateUID());
                        results.add(attrs);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Erro ao consultar RIS: {}", e.getMessage());
        }
        return results;
    }

    private List<byte[]> generateExamImages(String accessionNumber, String patientName, String patientID, String studyInstanceUID, String modality) throws Exception {
        List<byte[]> images = new ArrayList<>();
        File templateFile = pickRandomTemplateFile();
        for (int i = 1; i <= 3; i++) {
            if (templateFile != null && templateFile.exists()) {
                images.add(patchTemplate(templateFile, accessionNumber, patientName, patientID, studyInstanceUID, modality, i));
            } else {
                images.add(generateSyntheticImage(accessionNumber, patientName, patientID, studyInstanceUID, modality, i));
            }
        }
        return images;
    }

    private File pickRandomTemplateFile() {
        try {
            File dir = new File(templatesPath);
            if (!dir.exists() || !dir.isDirectory()) return null;
            File[] files = dir.listFiles(f -> f.isFile() && f.length() > 0);
            if (files == null || files.length == 0) return null;
            return files[new Random().nextInt(files.length)];
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] patchTemplate(File templateFile, String accessionNumber, String patientName, String patientID, String studyInstanceUID, String modality, int instanceNumber) throws IOException {
        try (DicomInputStream dis = new DicomInputStream(templateFile)) {
            Attributes dataset = dis.readDataset(-1, -1);
            Attributes fmi = dis.getFileMetaInformation();
            dataset.setString(Tag.PatientName, VR.PN, patientName);
            dataset.setString(Tag.PatientID, VR.LO, patientID);
            dataset.setString(Tag.AccessionNumber, VR.SH, accessionNumber);
            dataset.setString(Tag.StudyInstanceUID, VR.UI, studyInstanceUID);
            dataset.setString(Tag.SeriesInstanceUID, VR.UI, generateUID());
            dataset.setString(Tag.SOPInstanceUID, VR.UI, generateUID());
            dataset.setString(Tag.Modality, VR.CS, modality);
            dataset.setInt(Tag.InstanceNumber, VR.IS, instanceNumber);
            
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String nowTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
            dataset.setString(Tag.InstanceCreationDate, VR.DA, now);
            dataset.setString(Tag.InstanceCreationTime, VR.TM, nowTime);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DicomOutputStream dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)) {
                dos.writeDataset(fmi, dataset);
            }
            return baos.toByteArray();
        }
    }

    private byte[] generateSyntheticImage(String accessionNumber, String patientName, String patientID, String studyInstanceUID, String modality, int i) throws Exception {
        Attributes dcmAttrs = new Attributes();
        dcmAttrs.setString(Tag.PatientName, VR.PN, patientName);
        dcmAttrs.setString(Tag.PatientID, VR.LO, patientID);
        dcmAttrs.setString(Tag.AccessionNumber, VR.SH, accessionNumber);
        dcmAttrs.setString(Tag.StudyInstanceUID, VR.UI, studyInstanceUID);
        dcmAttrs.setString(Tag.SeriesInstanceUID, VR.UI, generateUID());
        dcmAttrs.setString(Tag.SOPInstanceUID, VR.UI, generateUID());
        dcmAttrs.setString(Tag.SOPClassUID, VR.UI, modality.equals("MR") ? UID.MRImageStorage : UID.UltrasoundImageStorage);

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String nowTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        dcmAttrs.setString(Tag.StudyDate, VR.DA, now);
        dcmAttrs.setString(Tag.StudyTime, VR.TM, nowTime);
        dcmAttrs.setString(Tag.Modality, VR.CS, modality);
        dcmAttrs.setInt(Tag.SeriesNumber, VR.IS, 1);
        dcmAttrs.setInt(Tag.InstanceNumber, VR.IS, i);
        dcmAttrs.setString(Tag.Manufacturer, VR.LO, "OmniLaudo Mock Modality");
        dcmAttrs.setInt(Tag.Rows, VR.US, 512);
        dcmAttrs.setInt(Tag.Columns, VR.US, 512);
        dcmAttrs.setInt(Tag.BitsAllocated, VR.US, 8);
        dcmAttrs.setInt(Tag.BitsStored, VR.US, 8);
        dcmAttrs.setInt(Tag.HighBit, VR.US, 7);
        dcmAttrs.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");

        byte[] pixelData = generateMockPixelData(512, 512, i);
        dcmAttrs.setBytes(Tag.PixelData, VR.OB, pixelData);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DicomOutputStream dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)) {
            dos.writeDataset(null, dcmAttrs);
        }
        return baos.toByteArray();
    }

    private DicomSendResponse sendDicomViaCStore(byte[] dicomData, String identifier) {
        try {
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
            rq.addPresentationContext(new PresentationContext(1, UID.UltrasoundImageStorage, UID.ImplicitVRLittleEndian));
            rq.addPresentationContext(new PresentationContext(3, UID.MRImageStorage, UID.ImplicitVRLittleEndian));

            Association as = ae.connect(conn, rq);
            try {
                Attributes dataset;
                Attributes fmi;
                try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(dicomData))) {
                    fmi = dis.getFileMetaInformation();
                    dataset = dis.readDataset(-1, -1);
                }
                String cuid = fmi.getString(Tag.MediaStorageSOPClassUID);
                String iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID);
                DimseRSP rsp = as.cstore(cuid, iuid, 0, new DataWriterAdapter(dataset), null);
                rsp.next();
                return DicomSendResponse.builder().success(true).status("SUCCESS").accessionNumber(identifier).build();
            } finally {
                as.release();
            }
        } catch (Exception e) {
            return DicomSendResponse.builder().success(false).status("ERROR").message(e.getMessage()).accessionNumber(identifier).build();
        }
    }

    private DicomSendResponse sendDicomViaCStoreWithFallback(byte[] dicomData, String identifier) {
        DicomSendResponse resp = sendDicomViaCStore(dicomData, identifier);
        if (resp.isSuccess()) return resp;
        return sendToOrthancRest(dicomData, identifier);
    }

    private DicomSendResponse sendToOrthancRest(byte[] dicomData, String identifier) {
        try {
            String orthancStudyId = sendToOrthanc(dicomData, identifier);
            return DicomSendResponse.builder().success(true).status("SUCCESS").accessionNumber(identifier).orthancStudyId(orthancStudyId).build();
        } catch (Exception e) {
            return DicomSendResponse.builder().success(false).status("ERROR").message(e.getMessage()).accessionNumber(identifier).build();
        }
    }

    public DicomSendResponse simulateAndSendExam(ExamSimulationRequest request) {
        try {
            byte[] dicomData = generateMockDicom(request);
            String orthancStudyId = sendToOrthanc(dicomData, request.getAccessionNumber());
            return DicomSendResponse.builder().success(true).status("SUCCESS").accessionNumber(request.getAccessionNumber()).orthancStudyId(orthancStudyId).build();
        } catch (Exception e) {
            return DicomSendResponse.builder().success(false).status("ERROR").message(e.getMessage()).accessionNumber(request.getAccessionNumber()).build();
        }
    }

    public DicomSendResponse uploadAndSendDicom(ExamSimulationRequest request, byte[] dicomData) {
        try {
            byte[] validatedDicom = ensureAccessionOnDicom(dicomData, request.getAccessionNumber());
            String orthancStudyId = sendToOrthanc(validatedDicom, request.getAccessionNumber());
            return DicomSendResponse.builder().success(true).status("SUCCESS").accessionNumber(request.getAccessionNumber()).orthancStudyId(orthancStudyId).build();
        } catch (Exception e) {
            return DicomSendResponse.builder().success(false).status("ERROR").message(e.getMessage()).accessionNumber(request.getAccessionNumber()).build();
        }
    }

    public DicomSendResponse finalizarExame(String accessionNumber) {
        try {
            StoredDicom stored = tempDicomStorage.remove(accessionNumber);
            if (stored == null) return DicomSendResponse.builder().success(false).status("NOT_FOUND").accessionNumber(accessionNumber).build();
            String orthancStudyId = sendToOrthanc(stored.getDicomData(), accessionNumber);
            return DicomSendResponse.builder().success(true).status("FINALIZED").accessionNumber(accessionNumber).orthancStudyId(orthancStudyId).build();
        } catch (Exception e) {
            return DicomSendResponse.builder().success(false).status("ERROR").message(e.getMessage()).accessionNumber(accessionNumber).build();
        }
    }

    private byte[] ensureAccessionOnDicom(byte[] dicomData, String accessionNumber) throws IOException {
        try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(dicomData))) {
            Attributes fmi = dis.getFileMetaInformation();
            Attributes dataset = dis.readDataset(-1, -1);
            dataset.setString(Tag.AccessionNumber, VR.SH, accessionNumber);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DicomOutputStream dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)) {
                if (fmi != null) dos.writeFileMetaInformation(fmi);
                dos.writeDataset(fmi, dataset);
            }
            return baos.toByteArray();
        }
    }

    private byte[] generateMockDicom(ExamSimulationRequest request) throws Exception {
        Attributes dcmAttrs = new Attributes();
        dcmAttrs.setString(Tag.PatientName, VR.PN, request.getPatientName());
        dcmAttrs.setString(Tag.PatientID, VR.LO, String.valueOf(request.getPatientId()));
        dcmAttrs.setString(Tag.AccessionNumber, VR.SH, request.getAccessionNumber());
        dcmAttrs.setString(Tag.StudyInstanceUID, VR.UI, generateUID());
        dcmAttrs.setString(Tag.SeriesInstanceUID, VR.UI, generateUID());
        dcmAttrs.setString(Tag.SOPInstanceUID, VR.UI, generateUID());
        dcmAttrs.setString(Tag.SOPClassUID, VR.UI, UID.UltrasoundImageStorage);
        dcmAttrs.setString(Tag.Modality, VR.CS, request.getModality() != null ? request.getModality() : "US");
        dcmAttrs.setInt(Tag.Rows, VR.US, 512);
        dcmAttrs.setInt(Tag.Columns, VR.US, 512);
        byte[] pixelData = generateMockPixelData(512, 512, 0);
        dcmAttrs.setBytes(Tag.PixelData, VR.OB, pixelData);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DicomOutputStream dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)) {
            dos.writeDataset(null, dcmAttrs);
        }
        return baos.toByteArray();
    }

    private byte[] generateMockPixelData(int rows, int cols, int imageIndex) {
        byte[] pixelData = new byte[rows * cols];
        for (int i = 0; i < rows * cols; i++) pixelData[i] = (byte) ((i + imageIndex * 50) % 256);
        return pixelData;
    }

    private String generateUID() {
        return "1.2.826.0.1.3680043.8.498." + System.currentTimeMillis() + "." + new Random().nextInt(10000);
    }

    private String sendToOrthanc(byte[] dicomData, String accessionNumber) throws Exception {
        var webClient = webClientBuilder.build();
        var requestSpec = webClient.post().uri(orthancUrl + "/instances");
        if (orthancUsername != null && !orthancUsername.isEmpty() && orthancPassword != null && !orthancPassword.isEmpty()) {
            requestSpec = requestSpec.headers(headers -> headers.setBasicAuth(orthancUsername, orthancPassword));
        }
        var response = requestSpec.bodyValue(dicomData).retrieve().bodyToMono(Map.class).block();
        if (response != null && response.containsKey("ID")) {
            String instanceId = (String) response.get("ID");
            String parentStudy = (String) response.get("ParentStudy");
            if (parentStudy != null) return parentStudy;
            var studyRequestSpec = webClient.get().uri(orthancUrl + "/instances/" + instanceId);
            if (orthancUsername != null && !orthancUsername.isEmpty()) {
                studyRequestSpec = studyRequestSpec.headers(headers -> headers.setBasicAuth(orthancUsername, orthancPassword));
            }
            Map<String, Object> instanceInfo = studyRequestSpec.retrieve().bodyToMono(Map.class).block();
            if (instanceInfo != null && instanceInfo.containsKey("ParentStudy")) return (String) instanceInfo.get("ParentStudy");
        }
        throw new Exception("Falha ao enviar DICOM para Orthanc");
    }
}

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
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class DicomGeneratorService {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

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

    /**
     * Simula um equipamento de modalidade: consulta worklist, gera 3 DICOMs e envia via C-STORE
     */
    public List<DicomSendResponse> simulateModality() {
        List<DicomSendResponse> responses = new ArrayList<>();
        try {
            log.info("🔍 Iniciando simulação de modalidade: consultando worklist...");

            // 1. C-FIND worklist
            List<Attributes> worklistItems = queryWorklist();
            log.info("📋 Worklist retornou {} itens", worklistItems.size());

            for (Attributes worklistItem : worklistItems) {
                try {
                    // Extrair dados do worklist
                    String accessionNumber = worklistItem.getString(Tag.AccessionNumber);
                    String patientName = worklistItem.getString(Tag.PatientName);
                    String patientID = worklistItem.getString(Tag.PatientID);
                    String studyInstanceUID = worklistItem.getString(Tag.StudyInstanceUID);
                    String modality = worklistItem.getString(Tag.Modality) != null ? worklistItem.getString(Tag.Modality) : "US";

                    log.info("🎬 Processando exame: Accession={}, Paciente={}", accessionNumber, patientName);

                    // 2. Gerar 3 DICOMs para este exame
                    List<byte[]> dicomImages = generateExamImages(accessionNumber, patientName, patientID, studyInstanceUID, modality);

                    // 3. Enviar cada imagem via C-STORE
                    for (int i = 0; i < dicomImages.size(); i++) {
                        DicomSendResponse response = sendDicomViaCStore(dicomImages.get(i), accessionNumber + "_img" + (i+1));
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
            log.error("❌ Erro na simulação de modalidade: {}", e.getMessage());
            responses.add(DicomSendResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message("Error in modality simulation: " + e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build());
        }

        return responses;
    }
    private List<Attributes> queryWorklist() throws Exception {
        List<Attributes> results = new ArrayList<>();

        ApplicationEntity ae = new ApplicationEntity(modalityAET);
        Connection conn = new Connection();
        conn.setHostname(dicomHost);
        conn.setPort(dicomPort);
        ae.addConnection(conn);

        Device device = new Device("mock-modality");
        device.addConnection(conn);
        device.addApplicationEntity(ae);

        AAssociateRQ rq = new AAssociateRQ();
        rq.setCallingAET(modalityAET);
        rq.setCalledAET(orthancAET);
        rq.addPresentationContext(new PresentationContext(1, UID.ModalityWorklistInformationModelFind, UID.ImplicitVRLittleEndian));

        Association as = ae.connect(conn, null, rq);
        try {
            // Criar query dataset
            Attributes query = new Attributes();
            query.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
            // Adicionar filtros se necessário, por exemplo:
            // query.setString(Tag.AccessionNumber, VR.SH, "*");
            // query.setString(Tag.PatientName, VR.PN, "*");

            DimseRSP rsp = as.cfind(UID.ModalityWorklistInformationModelFind, 1, query, null, 0);
            while (rsp.next()) {
                Attributes dataset = rsp.getDataset();
                if (dataset != null) {
                    results.add(dataset);
                }
            }
        } finally {
            as.release();
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
            ApplicationEntity ae = new ApplicationEntity(modalityAET);
            Connection conn = new Connection();
            conn.setHostname(dicomHost);
            conn.setPort(dicomPort);
            ae.addConnection(conn);

            Device device = new Device("mock-modality");
            device.addConnection(conn);
            device.addApplicationEntity(ae);

            AAssociateRQ rq = new AAssociateRQ();
            rq.setCallingAET(modalityAET);
            rq.setCalledAET(orthancAET);
            rq.addPresentationContext(new PresentationContext(1, UID.UltrasoundImageStorage, UID.ImplicitVRLittleEndian));
            rq.addPresentationContext(new PresentationContext(3, UID.MRImageStorage, UID.ImplicitVRLittleEndian));

            Association as = ae.connect(conn, null, rq);
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

            // Busca informações do estudo
            var studyRequestSpec = webClient.get()
                    .uri(orthancUrl + "/instances/" + instanceId);

            // Adiciona autenticação se credenciais estiverem configuradas
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

# 🎬 OmniLaudo Mock Modality

Servidor simulador de máquina de ultrassom/modalidade para testes end-to-end da integração RIS/PACS.

## 🎯 Propósito

Este projeto oferece uma API REST que simula o comportamento de uma máquina de ultrassom:
1. Recebe um **Accession Number** (identificador único do exame no RIS)
2. Recebe dados do **Paciente**
3. Gera um arquivo **DICOM mockado** com dados realistas
4. Envia o DICOM para o **Orthanc (PACS)**
5. O `SincronizadorDicomService` do OmniLaudo detecta automaticamente e vincula o exame ao agendamento

## 🏗️ Fluxo de Teste

```
Você (Postman/cURL)
    ↓
POST /api/v1/exams/simulate
    ↓
Mock Modality (gera DICOM)
    ↓
Upload para Orthanc
    ↓
SincronizadorDicomService (polling a cada 30s)
    ↓
→ Encontra AccessionNumber
→ Busca agendamento correspondente no PostgreSQL
→ Marca como REALIZADO
→ Médico pode iniciar laudo
```

## 📋 Endpoints

### 1. **Simular Execução de Exame**
```bash
POST /api/v1/exams/simulate
Content-Type: application/json

{
  "accession_number": "ACC-123456",
  "patient_id": 1,
  "patient_name": "João Silva",
  "exam_type": "ULTRASSOM ABDOMEN",
  "modality": "US",
  "description": "Ultrassom abdominal para investigação de dor"
}
```

**Resposta (201 Created):**
```json
{
  "success": true,
  "status": "SUCCESS",
  "message": "Exam simulated and sent to Orthanc successfully",
  "accession_number": "ACC-123456",
  "orthanc_study_id": "1234567890abc",
  "timestamp": 1712500123456
}
```

### 2. **Health Check**
```bash
GET /api/v1/exams/health
```

Resposta: `Mock Modality is running ✅`

### 3. **Informações da API**
```bash
GET /api/v1/exams/info
```

## 🚀 Como Usar

### Prerequisitos
- Docker e Docker Compose instalados
- OmniLaudo-API rodando
- PostgreSQL rodando
- Orthanc rodando

### 1. **Inicie os serviços**
```bash
cd /path/to/OmniLaudo-api
docker-compose up
```

Aguarde até que todos os serviços estejam online:
- PostgreSQL: porta 5433
- Orthanc: porta 8042 (API REST) e 4242 (DICOM)
- **Mock Modality: porta 8081**
- OmniLaudo-API: porta 8080

### 2. **Crie um Agendamento no OmniLaudo**
Via a API do OmniLaudo ou interface web:
```bash
POST /api/v1/agendamentos
{
  "paciente_id": 1,
  "equipamento_id": 1,
  "data_agendamento": "2024-04-10T14:00:00",
  "tipo_exame": "ULTRASSOM"
  # Será gerado accession_number automaticamente (ex: ACC-123456)
}
```

Anote o **Accession Number** gerado.

### 3. **Simule a Execução do Exame**
```bash
curl -X POST http://localhost:8081/api/v1/exams/simulate \
  -H "Content-Type: application/json" \
  -d '{
    "accession_number": "ACC-123456",
    "patient_id": 1,
    "patient_name": "João Silva",
    "exam_type": "ULTRASSOM ABDOMEN",
    "modality": "US",
    "description": "Ultrassom abdominal completo"
  }'
```

### 4. **Verifique o Fluxo**

1. ✅ Mock Modality gera DICOM e envia para Orthanc
2. ✅ Aguarde até 30 segundos (período de polling)
3. ✅ O `SincronizadorDicomService` detecta e vincula
4. ✅ Consulte o agendamento:
   ```bash
   GET /api/v1/agendamentos/{agendamento_id}
   ```
   O status deve ser **REALIZADO**

## 🔧 Configurações

No arquivo `src/main/resources/application.properties`:

```properties
# Porta da aplicação
server.port=8081

# URL do Orthanc (quando rodando em Docker)
modality.orthanc.url=http://orthanc:8042

# Autenticação do Orthanc (se habilitada)
modality.orthanc.username=
modality.orthanc.password=

# Loglevel
logging.level.com.omnilaudo.mockmodality=DEBUG
```

## 🛠️ Desenvolvimento Local

### Build
```bash
mvn clean package
```

### Run
```bash
java -jar target/mock-modality-0.0.1-SNAPSHOT.jar
```

### Testes
```bash
mvn test
```

## 📊 Estrutura do Projeto

```
mock-modality/
├── pom.xml                           # Dependências Maven
├── Dockerfile                        # Build multi-stage para Docker
├── README.md                         # Este arquivo
└── src/
    ├── main/
    │   ├── java/com/omnilaudo/mockmodality/
    │   │   ├── MockModalityApplication.java    # Aplicação principal
    │   │   ├── controller/
    │   │   │   └── ExamSimulatorController.java  # REST endpoints
    │   │   ├── service/
    │   │   │   └── DicomGeneratorService.java    # Geração e envio DICOM
    │   │   └── dto/
    │   │       ├── ExamSimulationRequest.java    # DTO entrada
    │   │       └── DicomSendResponse.java        # DTO saída
    │   └── resources/
    │       └── application.properties           # Configurações
    └── test/
        └── java/com/omnilaudo/mockmodality/
```

## 🎓 Conceitos DICOM Utilizados

- **PatientID** (`0010,0020`): Identificador único do paciente
- **PatientName** (`0010,0010`): Nome do paciente
- **AccessionNumber** (`0008,0050`): Identificador único do exame (chave de matching RIS/PACS)
- **StudyInstanceUID** (`0020,000D`): UID único do estudo
- **SeriesInstanceUID** (`0020,000E`): UID único da série
- **SOPInstanceUID** (`0002,0003`): UID único da imagem
- **Modality** (`0008,0060`): Tipo de modalidade (US, CR, etc.)
- **PixelData** (`7FE0,0010`): Dados da imagem (mockado com gradiente simples)

## ⚠️ Limitações

- **Imagem DICOM mockada**: Gera apenas uma imagem simples com dados genéricos (suficiente para testes)
- **Sem autenticação HTTP**: Remove autenticação por simplicidade (adicione conforme necessário)
- **Sem validação HTTP/2**: Usa HTTP/1.1
- **Sem compressão DICOM**: Envia sempre descomprimido

## 🔗 Integração com OmniLaudo

Este servidor complementa o OmniLaudo substituindo a máquina de ultrassom real:

| Component | Papel |
|-----------|-------|
| **OmniLaudo RIS** | Gerencia agendamentos, gera Accession Numbers |
| **Mock Modality** | Simula máquina, gera e envia DICOMs |
| **Orthanc PACS** | Armazena DICOMs, disponibiliza para laudo |
| **SincronizadorDicomService** | Faz polling, detecta novas imagens, vincula agendamentos |

## 📝 Exemplo Completo de Teste

```bash
# 1. Criar agendamento
curl -X POST http://localhost:8080/api/v1/agendamentos \
  -H "Content-Type: application/json" \
  -d '{"paciente_id": 1, "equipamento_id": 1}'

# Resposta: {"id": 1, "accession_number": "ACC-20240410-001", ...}

# 2. Simular exame
curl -X POST http://localhost:8081/api/v1/exams/simulate \
  -H "Content-Type: application/json" \
  -d '{
    "accession_number": "ACC-20240410-001",
    "patient_id": 1,
    "patient_name": "João Silva"
  }'

# 3. Aguardar polling (30s)
sleep 35

# 4. Verificar status
curl http://localhost:8080/api/v1/agendamentos/1
# Status deve ser: REALIZADO
```

## 🤝 Contribuindo

Para melhorias, envie pull requests com:
- Melhor geração de imagens DICOM
- Suporte a autenticação com Orthanc
- Mais modalidades de imagem
- Suporte a envio de arquivos DICOM reais

## 📞 Suporte

Para dúvidas ou issues, consulte a documentação principal do OmniLaudo.

---

**Versão**: 0.0.1  
**Desenvolvido para**: OmniLaudo RIS/PACS Integration  
**Última atualização**: 2024

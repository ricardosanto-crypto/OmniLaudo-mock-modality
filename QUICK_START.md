# 🚀 Guia Rápido: Testando RIS/PACS com Mock Modality

## ⚡ Início Rápido (5 minutos)

### 1. Inicie todos os serviços
```bash
cd /path/to/OmniLaudo-api
docker-compose up
```

Aguarde até ver no terminal:
```
omnilaudo-db is ready to accept connections
omnilaudo-pacs is ready
omnilaudo-api is ready
omnilaudo-mock-modality is ready
```

### 2. Teste o Mock Modality
```bash
# Health check
curl http://localhost:8081/api/v1/exams/health
# Resposta: Mock Modality is running ✅
```

### 3. Simule um Exame
```bash
curl -X POST http://localhost:8081/api/v1/exams/simulate \
  -H "Content-Type: application/json" \
  -d '{
    "accession_number": "ACC-TEST-001",
    "patient_id": 1,
    "patient_name": "João Silva",
    "exam_type": "ULTRASSOM ABDOMEN",
    "modality": "US"
  }'
```

### 4. Aguarde 35 segundos (polling)
```bash
sleep 35
```

### 5. Verifique se o exame foi vinculado
```bash
# Verificar agendamentos (você precisa ter criado um antes)
curl http://localhost:8080/api/v1/agendamentos

# Verificar estudos no Orthanc
curl http://localhost:8042/studies
```

---

## 📋 Fluxo Completo Passo-a-Passo

### PASSO 1: Criar Paciente
```bash
curl -X POST http://localhost:8080/api/v1/pacientes \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "João Silva",
    "cpf": "12345678901",
    "email": "joao@example.com"
  }'

# Anote o ID retornado (ex: "id": 1)
```

### PASSO 2: Criar Agendamento (isso gera Accession Number!)
```bash
curl -X POST http://localhost:8080/api/v1/agendamentos \
  -H "Content-Type: application/json" \
  -d '{
    "paciente_id": 1,
    "equipamento_id": 1,
    "tipo_exame": "ULTRASSOM",
    "data_agendamento": "2024-04-10T10:00:00"
  }'

# Anote: "accession_number": "ACC-..."
```

### PASSO 3: Simular Execução do Exame com Mock Modality
```bash
# Use o Accession Number do agendamento
curl -X POST http://localhost:8081/api/v1/exams/simulate \
  -H "Content-Type: application/json" \
  -d '{
    "accession_number": "ACC-...",
    "patient_id": 1,
    "patient_name": "João Silva",
    "modality": "US"
  }'

# Resposta esperada:
# {
#   "success": true,
#   "status": "SUCCESS",
#   "accession_number": "ACC-...",
#   "orthanc_study_id": "abc123..."
# }
```

### PASSO 4: Aguarde Polling (~30s)
```bash
# SincronizadorDicomService verifica Orthanc a cada 30 segundos
# Ele vai:
# 1. Buscar lista de estudos no Orthanc
# 2. Para cada study, extrair AccessionNumber
# 3. Procurar agendamento com esse AccessionNumber
# 4. SE ENCONTRADO: atualizar status para REALIZADO
```

### PASSO 5: Confirme que Funcionou
```bash
# Verificar agendamento
curl http://localhost:8080/api/v1/agendamentos/1 | jq .

# Procure por: "status": "REALIZADO"
```

---

## 🧪 Exemplos de Requisições (Copie e Cole no Postman)

### Simular Ultrassom Abdomen
```json
POST http://localhost:8081/api/v1/exams/simulate

{
  "accession_number": "ACC-ABDOMEN-001",
  "patient_id": 1,
  "patient_name": "João Silva",
  "exam_type": "ULTRASSOM ABDOMEN",
  "modality": "US",
  "description": "Avaliação completa de abdômen"
}
```

### Simular Ultrassom Obstétrico
```json
POST http://localhost:8081/api/v1/exams/simulate

{
  "accession_number": "ACC-OB-001",
  "patient_id": 2,
  "patient_name": "Maria Santos",
  "exam_type": "ULTRASSOM OBSTETRICIA",
  "modality": "US",
  "description": "Ultrassom obstétrico 1º trimestre"
}
```

### Simular Raio-X Tórax
```json
POST http://localhost:8081/api/v1/exams/simulate

{
  "accession_number": "ACC-RX-001",
  "patient_id": 3,
  "patient_name": "Pedro Costa",
  "exam_type": "RAIO-X TORAX",
  "modality": "CR",
  "description": "Radiografia de tórax em PA e perfil"
}
```

---

## 📊 Verificações (Debugging)

### Ver Logs do Sincronizador
```bash
docker logs -f omnilaudo-api | grep SincronizadorDicomService
```

### Ver Logs do Mock Modality
```bash
docker logs -f omnilaudo-mock-modality
```

### Ver Estudos no Orthanc
```bash
curl http://localhost:8042/studies | jq '.'

# Listar um estudo específico
curl http://localhost:8042/studies/{study_id} | jq '.MainDicomTags'
```

### Verificar PostgreSQL
```bash
# Acessar banco de dados
psql -h localhost -p 5433 -U postgres -d omnilaudo

# Listar agendamentos
SELECT id, accession_number, status FROM agendamentos;

# Sair
\q
```

---

## ⚠️ Troubleshooting

### Problema: "Mock Modality is not running"
**Causa**: Container ainda não iniciou ou porta já está em uso  
**Solução**:
```bash
docker ps | grep mock-modality
docker logs omnilaudo-mock-modality
```

### Problema: "Connection refused ao enviar para Orthanc"
**Causa**: Orthanc não está rodando ou URL está errada  
**Solução**:
```bash
# Verificar se Orthanc está online
curl http://localhost:8042

# Ou no Docker
docker ps | grep orthanc
```

### Problema: "Agendamento não ficou REALIZADO"
**Causas possíveis**:
1. Accession Number não bate (verifique se é exatamente igual)
2. Polling ainda não rodou (aguarde 35 segundos)
3. SincronizadorDicomService pode estar desabilitado

**Solução**:
```bash
# Ver logs de sincronização
docker logs omnilaudo-api | tail -100

# Verificar status do agendamento
curl http://localhost:8080/api/v1/agendamentos/1 | jq '.status'

# Verificar estudo no Orthanc
curl http://localhost:8042/studies | jq '.'
```

### Problema: "DICOM enviado mas não aparece no Orthanc"
**Causa**: Credentials Orthanc ou rede  
**Solução**:
```bash
# Testar conexão Orthanc
curl -u:  http://localhost:8042/

# Ver logs ambos
docker logs omnilaudo-mock-modality | grep -i error
docker logs omnilaudo-pacs | tail -50
```

---

## 🔄 Ciclo Completo de Teste

```bash
# 1. Start
docker-compose up -d && sleep 10

# 2. Create Patient
PATIENT=$(curl -s -X POST http://localhost:8080/api/v1/pacientes \
  -H "Content-Type: application/json" \
  -d '{"nome":"Test","cpf":"123"}' | jq '.id')

# 3. Create Scheduling
ACCESSION=$(curl -s -X POST http://localhost:8080/api/v1/agendamentos \
  -H "Content-Type: application/json" \
  -d "{\"paciente_id\":$PATIENT}" | jq -r '.accession_number')

# 4. Simulate Exam
curl -s -X POST http://localhost:8081/api/v1/exams/simulate \
  -H "Content-Type: application/json" \
  -d "{\"accession_number\":\"$ACCESSION\",\"patient_id\":$PATIENT}"

# 5. Wait for polling
echo "Waiting 35 seconds for polling..."
sleep 35

# 6. Verify
curl http://localhost:8080/api/v1/agendamentos/$PATIENT | jq '.status'
# Output: "REALIZADO" ✅
```

---

## 📚 Referências Úteis

- **Mock Modality README**: `mock-modality/README.md`
- **Arquitetura RIS/PACS**: `docs/01 - Arquitetura e Fluxo de Sincronizacao.md`
- **Arquitetura Mock Modality**: `docs/02 - Arquitetura Mock Modality.md`
- **Postman Collection**: `mock-modality/postman_collection.json`
- **cURL Examples**: `mock-modality/examples-curl.sh`

---

**Última atualização**: 2024-04-10  
**Versão**: 1.0

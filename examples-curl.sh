#!/bin/bash
# OmniLaudo Mock Modality - Exemplo de Testes via cURL

echo "🎬 OmniLaudo Mock Modality - Examples"
echo ""

# Cores para output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# URLs
MOCK_URL="http://localhost:8081"
OMNILAUDO_URL="http://localhost:8080"

# ============================================================================
# 1. Health Check do Mock Modality
# ============================================================================
echo -e "${BLUE}1️⃣  Health Check - Verifica se Mock Modality está rodando${NC}"
echo "GET $MOCK_URL/api/v1/exams/health"
echo ""
curl -X GET "$MOCK_URL/api/v1/exams/health"
echo ""
echo ""

# ============================================================================
# 2. Info do Mock Modality
# ============================================================================
echo -e "${BLUE}2️⃣  Info - Informações sobre a API${NC}"
echo "GET $MOCK_URL/api/v1/exams/info"
echo ""
curl -X GET "$MOCK_URL/api/v1/exams/info"
echo ""
echo ""

# ============================================================================
# 3. Simular Exame - Exemplo 1 (Ultrassom Abdomen)
# ============================================================================
echo -e "${BLUE}3️⃣  Simular Exame - Ultrassom Abdomen${NC}"
echo "POST $MOCK_URL/api/v1/exams/simulate"
echo ""

ACCESSION_NUMBER="ACC-$(date +%s)"

echo "Usando Accession Number: $ACCESSION_NUMBER"
echo ""

RESPONSE=$(curl -X POST "$MOCK_URL/api/v1/exams/simulate" \
  -H "Content-Type: application/json" \
  -d "{
    \"accession_number\": \"$ACCESSION_NUMBER\",
    \"patient_id\": 1,
    \"patient_name\": \"João Silva\",
    \"exam_type\": \"ULTRASSOM ABDOMEN\",
    \"modality\": \"US\",
    \"description\": \"Ultrassom abdominal para investigação de dor\"
  }")

echo "$RESPONSE" | jq '.'
echo ""
echo ""

# ============================================================================
# 4. Simular Exame - Exemplo 2 (Ultrassom Obstétrico)
# ============================================================================
echo -e "${BLUE}4️⃣  Simular Exame - Ultrassom Obstétrico${NC}"
echo "POST $MOCK_URL/api/v1/exams/simulate"
echo ""

ACCESSION_NUMBER2="ACC-OB-$(date +%s)"

echo "Usando Accession Number: $ACCESSION_NUMBER2"
echo ""

RESPONSE2=$(curl -X POST "$MOCK_URL/api/v1/exams/simulate" \
  -H "Content-Type: application/json" \
  -d "{
    \"accession_number\": \"$ACCESSION_NUMBER2\",
    \"patient_id\": 2,
    \"patient_name\": \"Maria Santos\",
    \"exam_type\": \"ULTRASSOM OBSTETRICIA\",
    \"modality\": \"US\",
    \"description\": \"Ultrassom obstétrico - 1º trimestre\"
  }")

echo "$RESPONSE2" | jq '.'
echo ""
echo ""

# ============================================================================
# 5. Simular Exame - Exemplo 3 (Raio-X Tórax)
# ============================================================================
echo -e "${BLUE}5️⃣  Simular Exame - Raio-X Tórax${NC}"
echo "POST $MOCK_URL/api/v1/exams/simulate"
echo ""

ACCESSION_NUMBER3="ACC-RX-$(date +%s)"

echo "Usando Accession Number: $ACCESSION_NUMBER3"
echo ""

RESPONSE3=$(curl -X POST "$MOCK_URL/api/v1/exams/simulate" \
  -H "Content-Type: application/json" \
  -d "{
    \"accession_number\": \"$ACCESSION_NUMBER3\",
    \"patient_id\": 3,
    \"patient_name\": \"Pedro Costa\",
    \"exam_type\": \"RAIO-X TORAX\",
    \"modality\": \"CR\",
    \"description\": \"Radiografia de tórax em PA e perfil\"
  }")

echo "$RESPONSE3" | jq '.'
echo ""
echo ""

# ============================================================================
# 6. Instruções para Verificação
# ============================================================================
echo -e "${YELLOW}✅ PRÓXIMAS ETAPAS:${NC}"
echo ""
echo "1. Aguarde 30-35 segundos para o polling de sincronização"
echo ""
echo "2. Verifique os agendamentos no OmniLaudo:"
echo ""
echo "   GET $OMNILAUDO_URL/api/v1/agendamentos"
echo ""
echo "   curl -X GET '$OMNILAUDO_URL/api/v1/agendamentos' | jq '.'"
echo ""
echo "3. Verifique um agendamento específico:"
echo ""
echo "   GET $OMNILAUDO_URL/api/v1/agendamentos/{id}"
echo ""
echo "4. Verifique os estudos no Orthanc:"
echo ""
echo "   GET http://localhost:8042/studies"
echo ""
echo "5. Consulte os logs do SincronizadorDicomService:"
echo ""
echo "   docker logs omnilaudo-api | grep SincronizadorDicomService"
echo ""
echo -e "${GREEN}✨ Fluxo de teste completo!${NC}"

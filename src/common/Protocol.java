package common;

/**
 * ================================================================================
 * PROTOCOL.JAVA - Constantes do Protocolo de Comunicação
 * ================================================================================
 *
 * PAPEL NO SISTEMA:
 * -----------------
 * Esta classe é o "contrato" entre cliente e servidor. Define todos os códigos
 * numéricos que identificam operações e estados de resposta. É partilhada por
 * ambos os lados (está no pacote "common") para garantir consistência.
 *
 * DECISÃO DE DESIGN:
 * ------------------
 * Porquê usar constantes numéricas em vez de Strings ou Enums?
 *
 * 1. EFICIÊNCIA DE REDE: Inteiros ocupam sempre 4 bytes, enquanto strings
 *    variam em tamanho e requerem codificação/descodificação adicional.
 *
 * 2. SIMPLICIDADE DE PARSING: Ler um int é uma operação atómica
 *    (DataInputStream.readInt()), sem necessidade de parsing de texto.
 *
 * 3. COMPATIBILIDADE: Facilita futuras implementações em outras linguagens
 *    que podem não ter enums compatíveis com Java.
 *
 * Porquê não usar Java Enums?
 * - Enums são serializados como strings por defeito, aumentando o tamanho
 * - A serialização de enums entre versões diferentes pode causar problemas
 * - Inteiros são universalmente suportados em qualquer protocolo binário
 *
 * ORGANIZAÇÃO DOS CÓDIGOS:
 * ------------------------
 * Os códigos estão agrupados logicamente:
 * - 1-2: Autenticação (operações sem sessão)
 * - 3-4: Manipulação de dados (escrita)
 * - 5-8: Agregações (leitura analítica)
 * - 9, 12: Consultas (leitura simples)
 * - 10-11: Notificações (operações bloqueantes)
 * - 200, 500: Códigos de estado (inspirados em HTTP)
 *
 * POSSÍVEL PERGUNTA: "Porquê 200 e 500 para status?"
 * RESPOSTA: Seguimos a convenção HTTP onde 2xx indica sucesso e 5xx indica
 * erro do servidor. Isto torna o protocolo mais intuitivo para quem já
 * conhece protocolos web.
 */
public class Protocol {

    // ==================== COMANDOS DE AUTENTICAÇÃO ====================
    // Estes comandos podem ser executados SEM autenticação prévia.
    // São os únicos que o ClientHandler aceita antes do login.

    /**
     * Código para operação de registo de novo utilizador.
     *
     * FLUXO:
     * Cliente envia: [tag][REGISTER][username UTF][password UTF]
     * Servidor responde: [tag][STATUS_OK][1 byte: 1=sucesso, 0=user existe]
     *
     * NOTA: Não retornamos STATUS_ERR se o user existe porque não é um erro
     * de sistema - é uma condição de negócio válida que o cliente deve tratar.
     */
    public static final int REGISTER = 1;

    /**
     * Código para operação de autenticação (login).
     *
     * FLUXO:
     * Cliente envia: [tag][LOGIN][username UTF][password UTF]
     * Servidor responde: [tag][STATUS_OK][1 byte: 1=sucesso, 0=credenciais inválidas]
     *
     * DECISÃO: Não distinguimos entre "user não existe" e "password errada"
     * por razões de segurança - isto evita ataques de enumeração de utilizadores.
     */
    public static final int LOGIN = 2;

    // ==================== COMANDOS DE DADOS ====================
    // Requerem autenticação. Modificam o estado do servidor.

    /**
     * Código para adicionar um novo evento de venda.
     *
     * FLUXO:
     * Cliente envia: [tag][ADD_EVENT][produto UTF][quantidade int][preço double]
     * Servidor responde: [tag][STATUS_OK][vazio]
     *
     * NOTA: Eventos são adicionados ao buffer do dia atual (currentEvents no
     * StorageEngine) e só são persistidos quando se chama NEW_DAY.
     *
     * EFEITO COLATERAL: Também notifica o NotificationManager para verificar
     * condições de notificação (vendas simultâneas, consecutivas).
     */
    public static final int ADD_EVENT = 3;

    /**
     * Código para encerrar o dia atual e persistir os dados.
     *
     * FLUXO:
     * Cliente envia: [tag][NEW_DAY][vazio]
     * Servidor responde: [tag][STATUS_OK][vazio]
     *
     * OPERAÇÕES INTERNAS:
     * 1. StorageEngine.persistDay() - escreve eventos para ficheiro
     * 2. NotificationManager.newDay() - acorda threads bloqueadas e reset estado
     * 3. Limpeza de ficheiros fora da janela D
     *
     * ATOMICIDADE: Esta operação é atómica - ou tudo acontece ou nada acontece.
     * Se falhar a escrita do ficheiro, uma exceção é lançada e o dia NÃO avança.
     */
    public static final int NEW_DAY = 4;

    // ==================== COMANDOS DE AGREGAÇÃO ====================
    // Requerem autenticação. São operações de leitura sobre dados históricos.
    // Todos seguem o mesmo formato de pedido/resposta.

    /**
     * Código para consulta de quantidade total vendida.
     *
     * CÁLCULO: SUM(quantidade) para o produto nos últimos N dias
     *
     * EXEMPLO: Se produto "A" teve vendas de 10, 20, 30 unidades nos últimos
     * 3 dias, retorna 60.0
     */
    public static final int AGGR_QTY = 5;

    /**
     * Código para consulta de volume financeiro total.
     *
     * CÁLCULO: SUM(quantidade * preço) para o produto nos últimos N dias
     *
     * EXEMPLO: Se produto "A" teve vendas de (10 unid * 5€) e (20 unid * 3€),
     * retorna 110.0€
     */
    public static final int AGGR_VOL = 6;

    /**
     * Código para consulta de preço médio ponderado.
     *
     * CÁLCULO: SUM(quantidade * preço) / SUM(quantidade)
     *
     * NOTA: É uma média PONDERADA pela quantidade, não uma média simples dos
     * preços. Isto é mais correto para análise financeira.
     *
     * EXEMPLO: (10 unid * 5€) + (20 unid * 3€) = 110€ total
     *          10 + 20 = 30 unidades
     *          Média ponderada = 110/30 = 3.67€
     *
     * EDGE CASE: Se não houver vendas, retorna 0 (evita divisão por zero)
     */
    public static final int AGGR_AVG = 7;

    /**
     * Código para consulta de preço máximo registado.
     *
     * CÁLCULO: MAX(preço) para o produto nos últimos N dias
     *
     * NOTA: Retorna o preço unitário mais alto, independentemente da quantidade.
     */
    public static final int AGGR_MAX = 8;

    // ==================== COMANDOS DE CONSULTA ====================

    /**
     * Código para filtragem de eventos históricos por produto.
     *
     * FLUXO:
     * Cliente envia: [tag][FILTER][dia int][tamanho filtro int][produtos UTF...]
     * Servidor responde: [tag][STATUS_OK][dados comprimidos por dicionário]
     *
     * COMPRESSÃO POR DICIONÁRIO:
     * A resposta usa uma técnica onde strings repetidas são substituídas por
     * índices numéricos. Formato da resposta:
     * [tamanho dicionário][string1][string2]...[num eventos][idx prod][qtd][preço]...
     *
     * PORQUÊ COMPRESSÃO?
     * Se tivermos 1000 vendas do produto "iPhone 15 Pro Max 256GB", enviar
     * essa string 1000 vezes desperdiça largura de banda. Com dicionário,
     * enviamos a string 1 vez e depois apenas índices (4 bytes cada).
     */
    public static final int FILTER = 9;

    /**
     * Código para consulta do dia atual do servidor.
     *
     * FLUXO:
     * Cliente envia: [tag][GET_CURRENT_DAY][vazio]
     * Servidor responde: [tag][STATUS_OK][dia int]
     *
     * UTILIDADE: Permite ao cliente saber em que "dia lógico" o servidor está,
     * útil para validar parâmetros antes de enviar pedidos de filtro/agregação.
     */
    public static final int GET_CURRENT_DAY = 12;

    // ==================== COMANDOS DE NOTIFICAÇÃO ====================
    // Estes são comandos BLOQUEANTES - a thread do cliente fica parada à espera.

    /**
     * Código para aguardar venda simultânea de dois produtos.
     *
     * FLUXO:
     * Cliente envia: [tag][WAIT_SIMUL][produto1 UTF][produto2 UTF]
     * Servidor responde: [tag][STATUS_OK][1 byte: 1=condição cumprida, 0=dia terminou]
     *
     * SEMÂNTICA: A thread fica bloqueada até que AMBOS os produtos tenham sido
     * vendidos pelo menos uma vez no dia atual, OU até que alguém chame NEW_DAY.
     *
     * IMPLEMENTAÇÃO: O NotificationManager mantém um Set<String> dos produtos
     * vendidos no dia. Quando uma venda é registada, verifica se a condição
     * de cada thread em espera foi satisfeita.
     */
    public static final int WAIT_SIMUL = 10;

    /**
     * Código para aguardar sequência de vendas consecutivas.
     *
     * FLUXO:
     * Cliente envia: [tag][WAIT_CONSEC][N int]
     * Servidor responde: [tag][STATUS_OK][produto UTF ou vazio se dia terminou]
     *
     * SEMÂNTICA: Espera que um produto qualquer seja vendido N vezes seguidas
     * (sem vendas de outros produtos entre elas). Retorna o nome do produto
     * que atingiu a meta.
     *
     * EXEMPLO: Se N=3 e as vendas forem A, B, B, B, C, retorna "B".
     *
     * NOTA: Usa um Map<Integer, Set<String>> para guardar quais produtos
     * atingiram cada contagem. Isto permite que threads que começam a esperar
     * DEPOIS da condição já ter sido cumprida sejam notificadas imediatamente.
     */
    public static final int WAIT_CONSEC = 11;

    // ==================== CÓDIGOS DE RESPOSTA ====================
    // Usados no campo "type" das respostas do servidor para o cliente.

    /**
     * Código de resposta indicando sucesso na operação.
     *
     * Inspirado no HTTP 200 OK. O payload contém os dados da resposta,
     * cujo formato depende da operação solicitada.
     */
    public static final int STATUS_OK = 200;

    /**
     * Código de resposta indicando erro ou falha na operação.
     *
     * Inspirado no HTTP 500 Internal Server Error. O payload contém uma
     * mensagem de erro em formato string (bytes UTF-8).
     *
     * QUANDO É USADO:
     * - Cliente não autenticado tenta operação protegida
     * - Parâmetros inválidos (dia fora do intervalo, etc.)
     * - Erros de I/O no servidor
     * - Qualquer exceção não tratada
     */
    public static final int STATUS_ERR = 500;
}
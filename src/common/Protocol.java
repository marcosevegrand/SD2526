package common;

/**
 * Constantes do protocolo de comunicação entre cliente e servidor.
 *
 * Centraliza todos os códigos de operação e estados de resposta utilizados
 * na serialização de mensagens, evitando o uso de valores literais dispersos
 * pelo código e garantindo consistência entre os dois lados da comunicação.
 */
public class Protocol {

    // ==================== COMANDOS DE AUTENTICAÇÃO ====================

    /** Código para operação de registo de novo utilizador. */
    public static final int REGISTER = 1;

    /** Código para operação de autenticação (login). */
    public static final int LOGIN = 2;

    // ==================== COMANDOS DE DADOS ====================

    /** Código para adicionar um novo evento de venda. */
    public static final int ADD_EVENT = 3;

    /** Código para encerrar o dia atual e persistir os dados. */
    public static final int NEW_DAY = 4;

    // ==================== COMANDOS DE AGREGAÇÃO ====================

    /** Código para consulta de quantidade total vendida. */
    public static final int AGGR_QTY = 5;

    /** Código para consulta de volume financeiro total. */
    public static final int AGGR_VOL = 6;

    /** Código para consulta de preço médio ponderado. */
    public static final int AGGR_AVG = 7;

    /** Código para consulta de preço máximo registado. */
    public static final int AGGR_MAX = 8;

    // ==================== COMANDOS DE CONSULTA ====================

    /** Código para filtragem de eventos históricos por produto. */
    public static final int FILTER = 9;

    /** Código para consulta do dia atual do servidor. */
    public static final int GET_CURRENT_DAY = 12;

    // ==================== COMANDOS DE NOTIFICAÇÃO ====================

    /** Código para aguardar venda simultânea de dois produtos. */
    public static final int WAIT_SIMUL = 10;

    /** Código para aguardar sequência de vendas consecutivas. */
    public static final int WAIT_CONSEC = 11;

    // ==================== CÓDIGOS DE RESPOSTA ====================

    /** Código de resposta indicando sucesso na operação. */
    public static final int STATUS_OK = 200;

    /** Código de resposta indicando erro ou falha na operação. */
    public static final int STATUS_ERR = 500;
}
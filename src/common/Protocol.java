package common;

/**
 * Centraliza as constantes de sinalização do sistema.
 * A intenção é fornecer um vocabulário comum entre cliente e servidor, evitando "magic numbers"
 * e inconsistências durante a serialização.
 */
public class Protocol {

    /** Identificador para criação de conta. */
    public static final int REGISTER = 1;

    /** Identificador para verificação de credenciais. */
    public static final int LOGIN = 2;

    /** Comando para adicionar nova venda. */
    public static final int ADD_EVENT = 3;

    /** Comando para fechar o dia corrente. */
    public static final int NEW_DAY = 4;

    /** Agregação: Quantidade total. */
    public static final int AGGR_QTY = 5;

    /** Agregação: Volume financeiro. */
    public static final int AGGR_VOL = 6;

    /** Agregação: Média de preços. */
    public static final int AGGR_AVG = 7;

    /** Agregação: Preço máximo. */
    public static final int AGGR_MAX = 8;

    /** Comando de filtragem de dados históricos. */
    public static final int FILTER = 9;

    /** Espera por venda simultânea de dois produtos. */
    public static final int WAIT_SIMUL = 10;

    /** Espera por sequência de vendas consecutivas. */
    public static final int WAIT_CONSEC = 11;

    /** Comando para consultar o dia atual do servidor. */
    public static final int GET_CURRENT_DAY = 12;

    /** Resposta positiva do servidor. */
    public static final int STATUS_OK = 200;

    /** Resposta de erro ou falha de autorização. */
    public static final int STATUS_ERR = 500;
}

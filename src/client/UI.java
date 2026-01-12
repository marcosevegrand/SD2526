package client;

import common.Protocol;
import java.util.*;

/**
 * ================================================================================
 * UI.JAVA - Interface de Linha de Comando (CLI)
 * ================================================================================
 *
 * PAPEL NO SISTEMA:
 * -----------------
 * Esta classe é o ponto de entrada para utilizadores humanos. Fornece uma
 * interface textual interativa onde comandos são digitados e resultados
 * são apresentados no terminal.
 *
 * ARQUITETURA:
 * ------------
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │                          UTILIZADOR                                 │
 * │                             ↓ ↑                                     │
 * │  ┌─────────────────────────────────────────────────────────────┐   │
 * │  │                      UI.java                                 │   │
 * │  │  - Parsing de comandos                                       │   │
 * │  │  - Formatação de output                                      │   │
 * │  │  - Gestão de estado (loggedIn)                               │   │
 * │  └─────────────────────────────────────────────────────────────┘   │
 * │                             ↓ ↑                                     │
 * │  ┌─────────────────────────────────────────────────────────────┐   │
 * │  │                    ClientLib.java                            │   │
 * │  │  - API de alto nível                                         │   │
 * │  │  - Serialização/Desserialização                              │   │
 * │  └─────────────────────────────────────────────────────────────┘   │
 * │                             ↓ ↑                                     │
 * │                         [REDE TCP]                                  │
 * │                             ↓ ↑                                     │
 * │                          SERVIDOR                                   │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * SEPARAÇÃO DE RESPONSABILIDADES:
 * --------------------------------
 * - UI.java: Interação humana (parsing, formatting, validação básica)
 * - ClientLib.java: Lógica de comunicação (protocolo, rede, threading)
 *
 * Esta separação permite:
 * 1. Testar ClientLib sem interface gráfica
 * 2. Criar diferentes interfaces (GUI, web) sobre a mesma ClientLib
 * 3. Manutenção independente de cada camada
 *
 * MÁQUINA DE ESTADOS:
 * -------------------
 * A UI mantém um estado simples:
 *
 *    ┌──────────────┐  entrar (sucesso)   ┌───────────────────┐
 *    │  VISITANTE   │ ─────────────────→ │   AUTENTICADO      │
 *    │              │                     │                    │
 *    │ Comandos:    │                     │ Comandos:          │
 *    │ - registar   │                     │ - Todos de dados   │
 *    │ - entrar     │                     │ - Agregações       │
 *    │ - ajuda      │                     │ - Notificações     │
 *    │ - sair       │                     │                    │
 *    └──────────────┘                     └───────────────────┘
 *           ↑                                      │
 *           └──────────────────────────────────────┘
 *                          (sair)
 */
public class UI {

    /**
     * Ponto de entrada da aplicação cliente.
     *
     * FLUXO PRINCIPAL:
     * 1. Parsear argumentos de linha de comando
     * 2. Estabelecer conexão com o servidor
     * 3. Loop infinito de leitura de comandos
     * 4. Fechar conexão ao sair
     *
     * @param args Argumentos de linha de comando: [host] [port]
     *             host: endereço do servidor (default: localhost)
     *             port: porto do servidor (default: 12345)
     */
    public static void main(String[] args) {
        // ===== VALORES POR DEFEITO =====
        // Permitem correr o cliente sem argumentos para testes locais
        String host = "localhost";
        int port = 12345;

        // ===== PARSING DO HOST =====
        // Primeiro argumento opcional: endereço do servidor
        if (args.length > 0) {
            host = args[0];
            // Aceita hostname (localhost, server.example.com) ou IP (192.168.1.1)
        }

        // ===== PARSING DO PORTO =====
        // Segundo argumento opcional: porto do servidor
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);

                // ===== VALIDAÇÃO DO PORTO =====
                // Portos 0-1023 são "well-known ports" reservados para serviços
                // do sistema (HTTP=80, SSH=22, etc.). Requerem privilégios root.
                // Portos 1024-65535 são "registered" e "dynamic/private".
                if (port < 1024 || port > 65535) {
                    System.err.println("ERRO: Porto deve estar entre 1024 e 65535.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                // parseInt falhou - o argumento não é um número válido
                System.err.println("ERRO: Porto inválido: " + args[1]);
                System.exit(1);
            }
        }

        // ===== AVISO SOBRE ARGUMENTOS EXTRA =====
        // Não causa erro, apenas informa o utilizador
        if (args.length > 2) {
            System.err.println("AVISO: Argumentos excedentes ignorados.");
        }

        // ===== BLOCO TRY-WITH-RESOURCES =====
        // Garante que Scanner e ClientLib são fechados automaticamente
        // mesmo em caso de exceção. Isto é importante para:
        // - Libertar o socket TCP (ClientLib.close())
        // - Libertar handles do stdin (Scanner.close())
        try (
                Scanner sc = new Scanner(System.in);
                ClientLib lib = new ClientLib(host, port)
        ) {
            // ===== BANNER INICIAL =====
            // Apresentação visual e informações úteis
            System.out.println("========================================");
            System.out.println("   CLIENTE DE SÉRIES TEMPORAIS (SD)     ");
            System.out.println("========================================");
            System.out.println("Ligado a: " + host + ":" + port);
            System.out.println("Digite 'ajuda' para ver os comandos.");
            System.out.println("========================================\n");

            // ===== ESTADO DA SESSÃO =====
            // Controla se o utilizador pode executar operações de negócio
            boolean loggedIn = false;

            // ===== LOOP PRINCIPAL (REPL) =====
            // Read-Eval-Print Loop: ciclo básico de interfaces interativas
            while (true) {
                // ===== PROMPT =====
                // Indica visualmente o estado atual da sessão
                // Não inclui newline para que o input fique na mesma linha
                System.out.print(loggedIn ? "[Autenticado] > " : "[Visitante] > ");

                // ===== VERIFICAR EOF =====
                // hasNextLine() retorna false se stdin fechou (Ctrl+D no Unix)
                if (!sc.hasNextLine()) {
                    break;  // Sair do loop
                }

                // ===== LER E LIMPAR INPUT =====
                // trim() remove espaços em branco no início e fim
                String line = sc.nextLine().trim();

                // Ignorar linhas vazias (utilizador só pressionou Enter)
                if (line.isEmpty()) {
                    continue;
                }

                // ===== PARSING DO COMANDO =====
                // split("\\s+") divide por qualquer sequência de whitespace
                // Exemplo: "evento  produto  10  5.99" → ["evento", "produto", "10", "5.99"]
                String[] parts = line.split("\\s+");
                String cmd = parts[0].toLowerCase();  // Comandos case-insensitive

                try {
                    // ===== COMANDOS UNIVERSAIS =====
                    // Funcionam independentemente do estado de autenticação

                    if (cmd.equals("sair")) {
                        System.out.println("A encerrar ligação...");
                        break;  // Sair do loop → fecha conexão no finally
                    }

                    if (cmd.equals("ajuda")) {
                        printHelp();
                        continue;  // Voltar ao prompt
                    }

                    if (cmd.equals("limpar")) {
                        handleClear();
                        continue;
                    }

                    // ===== COMANDOS DE ACESSO =====
                    // Registar e entrar funcionam sem estar autenticado

                    if (cmd.equals("registar")) {
                        handleRegister(parts, lib);
                        continue;
                    }

                    if (cmd.equals("entrar")) {
                        // handleLogin retorna true/false que atualiza o estado
                        loggedIn = handleLogin(parts, lib);
                        continue;
                    }

                    // ===== VERIFICAÇÃO DE AUTENTICAÇÃO =====
                    // Todos os comandos abaixo requerem login prévio
                    if (!loggedIn) {
                        System.out.println(
                                "ERRO: Operação negada. Deve autenticar-se primeiro usando 'entrar'."
                        );
                        continue;
                    }

                    // ===== COMANDOS DE NEGÓCIO =====
                    // Só acessíveis após autenticação bem-sucedida
                    processBusinessCommands(cmd, parts, lib);

                } catch (Exception e) {
                    // ===== TRATAMENTO GENÉRICO DE ERROS =====
                    // Captura qualquer exceção para não crashar o programa
                    // Permite ao utilizador continuar a usar a aplicação
                    System.out.println("ERRO na execução: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            // ===== ERRO FATAL DE CONEXÃO =====
            // Não conseguimos estabelecer ou manter conexão com o servidor
            System.err.println(
                    "ERRO FATAL: O servidor não está acessível ou ocorreu um erro crítico."
            );
            e.printStackTrace();
        }
        // Aqui, o try-with-resources já fechou Scanner e ClientLib
    }

    // ==================== HANDLERS DE COMANDOS UTILITÁRIOS ====================

    /**
     * Limpa o ecrã do terminal usando sequências ANSI.
     *
     * COMO FUNCIONA:
     * Sequências ANSI são códigos de controlo interpretados pelo terminal:
     * - \033 = ESC (escape character)
     * - [H   = Move cursor para posição home (0,0)
     * - [2J  = Limpa todo o ecrã
     *
     * COMPATIBILIDADE:
     * - Funciona em: Linux, macOS, Windows Terminal, Git Bash
     * - NÃO funciona em: cmd.exe clássico do Windows
     */
    private static void handleClear() {
        // ===== SEQUÊNCIA ANSI PARA LIMPAR ECRÃ =====
        // \033[H  → Move cursor para (0,0)
        // \033[2J → Clear entire screen
        System.out.print("\033[H\033[2J");

        // ===== FLUSH OBRIGATÓRIO =====
        // System.out é buffered por defeito. Sem flush(), o texto pode
        // não aparecer imediatamente.
        System.out.flush();

        System.out.println("Consola limpa. Digite 'ajuda' se necessário.");
    }

    // ==================== HANDLERS DE AUTENTICAÇÃO ====================

    /**
     * Processa o comando de registo de utilizador.
     * Sintaxe: registar <username> <password>
     *
     * @param parts Argumentos do comando splitados
     * @param lib   Instância da biblioteca cliente
     * @throws Exception Se ocorrer erro de comunicação
     */
    private static void handleRegister(String[] parts, ClientLib lib)
            throws Exception {

        // ===== VALIDAÇÃO DE SINTAXE =====
        // parts[0] = "registar", parts[1] = user, parts[2] = pass
        if (parts.length != 3) {
            System.out.println("Uso incorreto. Sintáxe: registar <username> <password>");
            return;
        }

        // ===== CHAMADA À BIBLIOTECA =====
        // lib.register() faz todo o trabalho de rede e protocolo
        boolean ok = lib.register(parts[1], parts[2]);

        // ===== FEEDBACK AO UTILIZADOR =====
        // Mensagem clara sobre o resultado
        System.out.println(
                "Registo: " + (ok ? "CONCLUÍDO COM SUCESSO" : "FALHOU (Utilizador já existe)")
        );
    }

    /**
     * Processa o comando de autenticação.
     * Sintaxe: entrar <username> <password>
     *
     * @param parts Argumentos do comando
     * @param lib   Instância da biblioteca cliente
     * @return true se o login foi bem-sucedido, false caso contrário
     * @throws Exception Se ocorrer erro de comunicação
     */
    private static boolean handleLogin(String[] parts, ClientLib lib)
            throws Exception {

        // ===== VALIDAÇÃO DE SINTAXE =====
        if (parts.length != 3) {
            System.out.println("Uso incorreto. Sintáxe: entrar <username> <password>");
            return false;  // Não atualizar estado
        }

        // ===== CHAMADA À BIBLIOTECA =====
        boolean ok = lib.login(parts[1], parts[2]);

        // ===== FEEDBACK E RETORNO =====
        System.out.println("Login: " + (ok ? "SUCESSO" : "CREDENCIAIS INVÁLIDAS"));
        return ok;  // Retorna para atualizar loggedIn no main()
    }

    // ==================== HANDLERS DE COMANDOS DE NEGÓCIO ====================

    /**
     * Despacha comandos de negócio para as funções apropriadas.
     *
     * PADRÃO DISPATCHER:
     * Este método age como um "router" que direciona cada comando para
     * a função de handling apropriada. Mantém o main() limpo e organiza
     * a lógica por tipo de operação.
     *
     * @param cmd   Comando principal (já em lowercase)
     * @param parts Argumentos completos incluindo o comando
     * @param lib   Instância da biblioteca cliente
     * @throws Exception Se ocorrer erro de sintaxe ou comunicação
     */
    private static void processBusinessCommands(
            String cmd,
            String[] parts,
            ClientLib lib
    ) throws Exception {

        switch (cmd) {
            // ===== CONSULTA DO DIA ATUAL =====
            case "dia":
                int currentDay = lib.getCurrentDay();
                System.out.println("Dia atual no servidor: " + currentDay);
                break;

            // ===== REGISTO DE EVENTO DE VENDA =====
            case "evento":
                // Sintaxe: evento <produto> <quantidade> <preço>
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Sintáxe: evento <produto> <qtd> <preço>");
                }
                // ===== PARSING DOS ARGUMENTOS =====
                // parts[1] = produto (string)
                // parts[2] = quantidade (int) → parseInt pode lançar NumberFormatException
                // parts[3] = preço (double) → parseDouble pode lançar NumberFormatException
                lib.addEvent(
                        parts[1],
                        Integer.parseInt(parts[2]),
                        Double.parseDouble(parts[3])
                );
                System.out.println("Evento de venda registado.");
                break;

            // ===== ENCERRAMENTO DO DIA =====
            case "novodia":
                lib.newDay();
                System.out.println("Ciclo diário encerrado. Dados persistidos em disco.");
                break;

            // ===== AGREGAÇÕES ESTATÍSTICAS =====
            // Todos seguem o mesmo padrão: tipo <produto> <dias>
            case "qtd":
                handleAggr(Protocol.AGGR_QTY, parts, lib, "Quantidade Total");
                break;

            case "vol":
                handleAggr(Protocol.AGGR_VOL, parts, lib, "Volume Financeiro");
                break;

            case "media":
                handleAggr(Protocol.AGGR_AVG, parts, lib, "Preço Médio");
                break;

            case "max":
                handleAggr(Protocol.AGGR_MAX, parts, lib, "Preço Máximo");
                break;

            // ===== FILTRAGEM DE EVENTOS HISTÓRICOS =====
            case "filtrar":
                // Sintaxe: filtrar <dia> <produto1> [produto2] [produto3] ...
                // Mínimo 3 argumentos: filtrar, dia, pelo menos 1 produto
                if (parts.length < 3) {
                    throw new IllegalArgumentException("Sintáxe: filtrar <dia> <p1> [p2...]");
                }

                // ===== PARSING =====
                int day = Integer.parseInt(parts[1]);

                // ===== CONSTRUIR SET DE PRODUTOS =====
                // subList(2, length) → do índice 2 até ao fim
                // HashSet para O(1) lookup no servidor
                Set<String> filter = new HashSet<>(
                        Arrays.asList(parts).subList(2, parts.length)
                );

                // ===== CHAMADA À BIBLIOTECA =====
                List<String> results = lib.getEvents(day, filter);

                // ===== APRESENTAÇÃO DOS RESULTADOS =====
                System.out.println("--- Resultados do Filtro (Dia " + day + ") ---");
                if (results.isEmpty()) {
                    System.out.println("Nenhum evento encontrado.");
                } else {
                    // forEach com lambda para código conciso
                    // Prefixo "  " para indentação visual
                    results.forEach(s -> System.out.println("  " + s));
                }
                break;

            // ===== NOTIFICAÇÃO: VENDA SIMULTÂNEA =====
            case "simul":
                // Sintaxe: simul <produto1> <produto2>
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Sintáxe: simul <produto1> <produto2>");
                }

                // ===== AVISO DE BLOQUEIO =====
                // Importante informar o utilizador que vai ficar à espera
                System.out.println("A aguardar ocorrência simultânea... (Thread bloqueada)");

                // ===== OPERAÇÃO BLOQUEANTE =====
                // Esta chamada pode demorar minutos/horas até retornar
                boolean sim = lib.waitSimultaneous(parts[1], parts[2]);

                System.out.println(
                        "Notificação: " + (sim ? "A meta foi atingida!" : "O dia terminou sem ocorrência.")
                );
                break;

            // ===== NOTIFICAÇÃO: VENDAS CONSECUTIVAS =====
            case "consec":
                // Sintaxe: consec <N>
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Sintáxe: consec <N>");
                }

                System.out.println("A aguardar sequência consecutiva... (Thread bloqueada)");

                // ===== OPERAÇÃO BLOQUEANTE =====
                String res = lib.waitConsecutive(Integer.parseInt(parts[1]));

                System.out.println(
                        "Notificação: " + (res != null ? "Produto vencedor: " + res : "O dia terminou.")
                );
                break;

            // ===== COMANDO NÃO RECONHECIDO =====
            default:
                System.out.println("Comando desconhecido. Digite 'ajuda' para ver as opções.");
        }
    }

    /**
     * Processa comandos de agregação estatística.
     *
     * REFACTORING:
     * As 4 agregações (qtd, vol, media, max) têm código quase idêntico.
     * Este método extrai a lógica comum para evitar duplicação (DRY).
     *
     * @param type  Tipo de agregação (Protocol.AGGR_XXX)
     * @param parts Argumentos do comando
     * @param lib   Instância da biblioteca cliente
     * @param label Etiqueta para exibição ao utilizador
     * @throws Exception Se ocorrer erro de sintaxe ou comunicação
     */
    private static void handleAggr(
            int type,
            String[] parts,
            ClientLib lib,
            String label
    ) throws Exception {

        // Sintaxe: <comando> <produto> <dias>
        if (parts.length != 3) {
            throw new IllegalArgumentException("Sintáxe: " + parts[0] + " <produto> <dias>");
        }

        // ===== CHAMADA À BIBLIOTECA =====
        double res = lib.getAggregation(
                type,
                parts[1],
                Integer.parseInt(parts[2])
        );

        // ===== FORMATAÇÃO DO RESULTADO =====
        // printf com %.2f para mostrar 2 casas decimais
        // %s para strings, %n para newline portable
        System.out.printf(
                "%s para '%s' (%s dias): %.2f%n",
                label,
                parts[1],
                parts[2],
                res
        );
    }

    // ==================== AJUDA ====================

    /**
     * Exibe o manual de comandos disponíveis.
     *
     * DESIGN:
     * - Organizado por categoria para fácil navegação
     * - Exemplos de sintaxe para cada comando
     * - Descrição breve mas informativa
     */
    private static void printHelp() {
        System.out.println("\n--- MANUAL DE COMANDOS ---");

        // ===== COMANDOS DE ACESSO =====
        System.out.println("Acesso:");
        System.out.println("  registar <user> <pass>   - Cria uma nova conta no servidor.");
        System.out.println("  entrar <user> <pass>     - Autentica-se para aceder às funções.");
        System.out.println("  sair                     - Encerra a aplicação.");

        // ===== COMANDOS DE DADOS =====
        System.out.println("\nDados:");
        System.out.println("  dia                          - Consulta o dia atual no servidor.");
        System.out.println("  evento <prod> <qtd> <preco>  - Regista uma venda de um produto no dia atual.");
        System.out.println("  novodia                      - Fecha o dia atual e guarda os dados permanentemente.");
        System.out.println("  filtrar <dia> <p1> <p2> ...  - Lista vendas de certos produtos num dia passado.");

        // ===== COMANDOS DE ESTATÍSTICAS =====
        System.out.println("\nEstatísticas (histórico):");
        System.out.println("  qtd <prod> <dias>            - Soma da quantidade vendida nos últimos N dias.");
        System.out.println("  vol <prod> <dias>            - Valor financeiro total (qtd * preço) nos últimos N dias.");
        System.out.println("  media <prod> <dias>          - Preço médio ponderado nos últimos N dias.");
        System.out.println("  max <prod> <dias>            - Preço unitário máximo registado nos últimos N dias.");

        // ===== COMANDOS DE NOTIFICAÇÃO =====
        System.out.println("\nNotificações (bloqueante):");
        System.out.println("  simul <prod1> <prod2>        - Espera que ambos os produtos sejam vendidos no mesmo dia.");
        System.out.println("  consec <N>                   - Espera que um produto seja vendido N vezes seguidas.");

        System.out.println("--------------------------\n");
    }
}
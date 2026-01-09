package client;

import common.Protocol;
import java.util.*;

/**
 * Interface de Linha de Comando para o sistema de séries temporais.
 *
 * Fornece um ambiente interativo onde o utilizador pode executar todas as
 * operações suportadas pelo protocolo: autenticação, registo de eventos,
 * consultas estatísticas e monitorização em tempo real.
 */
public class UI {

    /**
     * Ponto de entrada da aplicação cliente.
     * Gere o ciclo de leitura de comandos e mantém o estado da sessão
     * (autenticado ou não autenticado).
     *
     * @param args Argumentos de linha de comando: [host] [port]
     */
    public static void main(String[] args) {
        String host = "localhost";
        int port = 12345;

        if (args.length > 0) {
            host = args[0];
        }

        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
                if (port < 1024 || port > 65535) {
                    System.err.println("ERRO: Porto deve estar entre 1024 e 65535.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("ERRO: Porto inválido: " + args[1]);
                System.exit(1);
            }
        }

        if (args.length > 2) {
            System.err.println("AVISO: Argumentos excedentes ignorados.");
        }

        try (
                Scanner sc = new Scanner(System.in);
                ClientLib lib = new ClientLib(host, port)
        ) {
            System.out.println("========================================");
            System.out.println("   CLIENTE DE SÉRIES TEMPORAIS (SD)     ");
            System.out.println("========================================");
            System.out.println("Ligado a: " + host + ":" + port);
            System.out.println("Digite 'ajuda' para ver os comandos.");
            System.out.println("========================================\n");

            boolean loggedIn = false;

            while (true) {
                System.out.print(loggedIn ? "[Autenticado] > " : "[Visitante] > ");

                if (!sc.hasNextLine()) {
                    break;
                }
                String line = sc.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split("\\s+");
                String cmd = parts[0].toLowerCase();

                try {
                    if (cmd.equals("sair")) {
                        System.out.println("A encerrar ligação...");
                        break;
                    }

                    if (cmd.equals("ajuda")) {
                        printHelp();
                        continue;
                    }

                    if (cmd.equals("limpar")) {
                        handleClear();
                        continue;
                    }

                    if (cmd.equals("registar")) {
                        handleRegister(parts, lib);
                        continue;
                    }

                    if (cmd.equals("entrar")) {
                        loggedIn = handleLogin(parts, lib);
                        continue;
                    }

                    if (!loggedIn) {
                        System.out.println(
                                "ERRO: Operação negada. Deve autenticar-se primeiro usando 'entrar'."
                        );
                        continue;
                    }

                    processBusinessCommands(cmd, parts, lib);
                } catch (Exception e) {
                    System.out.println("ERRO na execução: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println(
                    "ERRO FATAL: O servidor não está acessível ou ocorreu um erro crítico."
            );
            e.printStackTrace();
        }
    }

    /**
     * Limpa o ecrã do terminal usando sequências ANSI.
     */
    private static void handleClear() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
        System.out.println("Consola limpa. Digite 'ajuda' se necessário.");
    }

    /**
     * Processa o comando de registo de utilizador.
     *
     * @param parts Argumentos do comando
     * @param lib   Instância da biblioteca cliente
     * @throws Exception Se ocorrer erro de comunicação
     */
    private static void handleRegister(String[] parts, ClientLib lib)
            throws Exception {
        if (parts.length != 3) {
            System.out.println("Uso incorreto. Sintáxe: registar <username> <password>");
            return;
        }
        boolean ok = lib.register(parts[1], parts[2]);
        System.out.println(
                "Registo: " + (ok ? "CONCLUÍDO COM SUCESSO" : "FALHOU (Utilizador já existe)")
        );
    }

    /**
     * Processa o comando de autenticação.
     *
     * @param parts Argumentos do comando
     * @param lib   Instância da biblioteca cliente
     * @return true se o login foi bem-sucedido
     * @throws Exception Se ocorrer erro de comunicação
     */
    private static boolean handleLogin(String[] parts, ClientLib lib)
            throws Exception {
        if (parts.length != 3) {
            System.out.println("Uso incorreto. Sintáxe: entrar <username> <password>");
            return false;
        }
        boolean ok = lib.login(parts[1], parts[2]);
        System.out.println("Login: " + (ok ? "SUCESSO" : "CREDENCIAIS INVÁLIDAS"));
        return ok;
    }

    /**
     * Despacha comandos de negócio para as funções apropriadas.
     *
     * @param cmd   Comando principal
     * @param parts Argumentos completos
     * @param lib   Instância da biblioteca cliente
     * @throws Exception Se ocorrer erro de sintaxe ou comunicação
     */
    private static void processBusinessCommands(
            String cmd,
            String[] parts,
            ClientLib lib
    ) throws Exception {
        switch (cmd) {
            case "dia":
                int currentDay = lib.getCurrentDay();
                System.out.println("Dia atual no servidor: " + currentDay);
                break;

            case "evento":
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Sintáxe: evento <produto> <qtd> <preço>");
                }
                lib.addEvent(
                        parts[1],
                        Integer.parseInt(parts[2]),
                        Double.parseDouble(parts[3])
                );
                System.out.println("Evento de venda registado.");
                break;

            case "novodia":
                lib.newDay();
                System.out.println("Ciclo diário encerrado. Dados persistidos em disco.");
                break;

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

            case "filtrar":
                if (parts.length < 3) {
                    throw new IllegalArgumentException("Sintáxe: filtrar <dia> <p1> [p2...]");
                }
                int day = Integer.parseInt(parts[1]);
                Set<String> filter = new HashSet<>(
                        Arrays.asList(parts).subList(2, parts.length)
                );
                List<String> results = lib.getEvents(day, filter);
                System.out.println("--- Resultados do Filtro (Dia " + day + ") ---");
                if (results.isEmpty()) {
                    System.out.println("Nenhum evento encontrado.");
                } else {
                    results.forEach(s -> System.out.println("  " + s));
                }
                break;

            case "simul":
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Sintáxe: simul <produto1> <produto2>");
                }
                System.out.println("A aguardar ocorrência simultânea... (Thread bloqueada)");
                boolean sim = lib.waitSimultaneous(parts[1], parts[2]);
                System.out.println(
                        "Notificação: " + (sim ? "A meta foi atingida!" : "O dia terminou sem ocorrência.")
                );
                break;

            case "consec":
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Sintáxe: consec <N>");
                }
                System.out.println("A aguardar sequência consecutiva... (Thread bloqueada)");
                String res = lib.waitConsecutive(Integer.parseInt(parts[1]));
                System.out.println(
                        "Notificação: " + (res != null ? "Produto vencedor: " + res : "O dia terminou.")
                );
                break;

            default:
                System.out.println("Comando desconhecido. Digite 'ajuda' para ver as opções.");
        }
    }

    /**
     * Processa comandos de agregação estatística.
     *
     * @param type  Tipo de agregação
     * @param parts Argumentos do comando
     * @param lib   Instância da biblioteca cliente
     * @param label Etiqueta para exibição
     * @throws Exception Se ocorrer erro de sintaxe ou comunicação
     */
    private static void handleAggr(
            int type,
            String[] parts,
            ClientLib lib,
            String label
    ) throws Exception {
        if (parts.length != 3) {
            throw new IllegalArgumentException("Sintáxe: " + parts[0] + " <produto> <dias>");
        }
        double res = lib.getAggregation(
                type,
                parts[1],
                Integer.parseInt(parts[2])
        );
        System.out.printf(
                "%s para '%s' (%s dias): %.2f%n",
                label,
                parts[1],
                parts[2],
                res
        );
    }

    /**
     * Exibe o manual de comandos disponíveis.
     */
    private static void printHelp() {
        System.out.println("\n--- MANUAL DE COMANDOS ---");
        System.out.println("Acesso:");
        System.out.println("  registar <user> <pass>   - Cria uma nova conta no servidor.");
        System.out.println("  entrar <user> <pass>     - Autentica-se para aceder às funções.");
        System.out.println("  sair                     - Encerra a aplicação.");
        System.out.println("\nDados:");
        System.out.println("  dia                          - Consulta o dia atual no servidor.");
        System.out.println("  evento <prod> <qtd> <preco>  - Regista uma venda de um produto no dia atual.");
        System.out.println("  novodia                      - Fecha o dia atual e guarda os dados permanentemente.");
        System.out.println("  filtrar <dia> <p1> <p2> ...  - Lista vendas de certos produtos num dia passado.");
        System.out.println("\nEstatísticas (histórico):");
        System.out.println("  qtd <prod> <dias>            - Soma da quantidade vendida nos últimos N dias.");
        System.out.println("  vol <prod> <dias>            - Valor financeiro total (qtd * preço) nos últimos N dias.");
        System.out.println("  media <prod> <dias>          - Preço médio ponderado nos últimos N dias.");
        System.out.println("  max <prod> <dias>            - Preço unitário máximo registado nos últimos N dias.");
        System.out.println("\nNotificações (bloqueante):");
        System.out.println("  simul <prod1> <prod2>        - Espera que ambos os produtos sejam vendidos no mesmo dia.");
        System.out.println("  consec <N>                   - Espera que um produto seja vendido N vezes seguidas.");
        System.out.println("--------------------------\n");
    }
}
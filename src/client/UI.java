package client;

import common.Protocol;
import java.util.*;

/**
 * Interface de Linha de Comando (CLI) para interação com o sistema de séries temporais.
 * A intenção desta classe é fornecer um ambiente interativo onde o utilizador pode
 * executar todas as operações suportadas pelo protocolo, desde a autenticação até
 * à monitorização de eventos em tempo real.
 */
public class UI {

    /**
     * Ponto de entrada da aplicação CLI.
     * Gere o ciclo de leitura de comandos e a manutenção do estado da sessão.
     * @param args Argumentos de linha de comando (não utilizados).
     */
    public static void main(String[] args) {
        try (
            Scanner sc = new Scanner(System.in);
            ClientLib lib = new ClientLib("localhost", 12345)
        ) {
            System.out.println("========================================");
            System.out.println("   CLIENTE DE SÉRIES TEMPORAIS (SD)     ");
            System.out.println("========================================");
            System.out.println("Digite 'ajuda' para ver os comandos.");

            boolean loggedIn = false;

            while (true) {
                // Indicador visual de estado de autenticação
                System.out.print(
                    loggedIn ? "[Autenticado] > " : "[Visitante] > "
                );

                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;

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

                    // Comando utilitário para limpar a consola
                    if (cmd.equals("limpar")) {
                        handleClear();
                        continue;
                    }

                    // Comandos de acesso (não requerem login)
                    if (cmd.equals("registar")) {
                        handleRegister(parts, lib);
                        continue;
                    }

                    if (cmd.equals("entrar")) {
                        loggedIn = handleLogin(parts, lib);
                        continue;
                    }

                    // Guarda de segurança para comandos de negócio
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
     * Limpa o ecrã do terminal utilizando sequências de escape ANSI.
     * A sequência \033[H move o cursor para o início e \033[2J limpa o conteúdo.
     */
    private static void handleClear() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
        System.out.println("Consola limpa. Digite 'ajuda' se necessário.");
    }

    /**
     * Gere a lógica de registo de utilizadores.
     * @param parts Argumentos do comando.
     * @param lib Instância da biblioteca.
     * @throws Exception Erros de rede.
     */
    private static void handleRegister(String[] parts, ClientLib lib)
        throws Exception {
        if (parts.length != 3) {
            System.out.println(
                "Uso incorreto. Sintaxe: registar <username> <password>"
            );
            return;
        }
        boolean ok = lib.register(parts[1], parts[2]);
        System.out.println(
            "Registo: " +
                (ok ? "CONCLUÍDO COM SUCESSO" : "FALHOU (Utilizador já existe)")
        );
    }

    /**
     * Gere a lógica de login.
     * @param parts Argumentos do comando.
     * @param lib Instância da biblioteca.
     * @return true se o login for bem-sucedido.
     * @throws Exception Erros de rede.
     */
    private static boolean handleLogin(String[] parts, ClientLib lib)
        throws Exception {
        if (parts.length != 3) {
            System.out.println(
                "Uso incorreto. Sintaxe: entrar <username> <password>"
            );
            return false;
        }
        boolean ok = lib.login(parts[1], parts[2]);
        System.out.println(
            "Login: " + (ok ? "SUCESSO" : "CREDENCIAIS INVÁLIDAS")
        );
        return ok;
    }

    /**
     * Despacha os comandos de negócio e estatística para as funções respetivas.
     * A intenção é manter o método principal limpo e organizar os comandos por categorias.
     * @param cmd Comando principal.
     * @param parts Argumentos.
     * @param lib Instância da biblioteca.
     * @throws Exception Se a sintaxe for inválida ou ocorrer erro na ClientLib.
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
                if (parts.length != 4) throw new IllegalArgumentException(
                    "Sintaxe: evento <produto> <qtd> <preço>"
                );
                lib.addEvent(
                    parts[1],
                    Integer.parseInt(parts[2]),
                    Double.parseDouble(parts[3])
                );
                System.out.println("Evento de venda registado.");
                break;
            case "novodia":
                lib.newDay();
                System.out.println(
                    "Ciclo diário encerrado. Dados persistidos em disco."
                );
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
                if (parts.length < 3) throw new IllegalArgumentException(
                    "Sintaxe: filtrar <dia> <p1> [p2...]"
                );
                int day = Integer.parseInt(parts[1]);
                Set<String> filter = new HashSet<>(
                    Arrays.asList(parts).subList(2, parts.length)
                );
                List<String> results = lib.getEvents(day, filter);
                System.out.println(
                    "--- Resultados do Filtro (Dia " + day + ") ---"
                );
                if (results.isEmpty()) System.out.println(
                    "Nenhum evento encontrado."
                );
                else results.forEach(s -> System.out.println("  " + s));
                break;
            case "simul":
                if (parts.length != 3) throw new IllegalArgumentException(
                    "Sintaxe: simul <produto1> <produto2>"
                );
                System.out.println(
                    "A aguardar ocorrência simultânea... (Thread bloqueada)"
                );
                boolean sim = lib.waitSimultaneous(parts[1], parts[2]);
                System.out.println(
                    "Notificação: " +
                        (sim
                            ? "A meta foi atingida!"
                            : "O dia terminou sem ocorrência.")
                );
                break;
            case "consec":
                if (parts.length != 2) throw new IllegalArgumentException(
                    "Sintaxe: consec <N>"
                );
                System.out.println(
                    "A aguardar sequência consecutiva... (Thread bloqueada)"
                );
                String res = lib.waitConsecutive(Integer.parseInt(parts[1]));
                System.out.println(
                    "Notificação: " +
                        (res != null
                            ? "Produto vencedor: " + res
                            : "O dia terminou.")
                );
                break;
            default:
                System.out.println(
                    "Comando desconhecido. Digite 'ajuda' para ver as opções."
                );
        }
    }

    /**
     * Auxiliar para processar todos os tipos de agregação estatística.
     * @param type Tipo de protocolo.
     * @param parts Argumentos.
     * @param lib Biblioteca.
     * @param label Texto para exibição.
     * @throws Exception Erros de parsing ou rede.
     */
    private static void handleAggr(
        int type,
        String[] parts,
        ClientLib lib,
        String label
    ) throws Exception {
        if (parts.length != 3) throw new IllegalArgumentException(
            "Sintaxe: " + parts[0] + " <produto> <dias>"
        );
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
     * Exibe o manual de instruções detalhado para o utilizador.
     * A intenção é servir de guia rápido para a sintaxe de cada comando.
     */
    private static void printHelp() {
        System.out.println("\n--- MANUAL DE COMANDOS ---");
        System.out.println("Acesso:");
        System.out.println(
            "  registar <user> <pass>   - Cria uma nova conta no servidor."
        );
        System.out.println(
            "  entrar <user> <pass>     - Autentica-se para aceder às funções."
        );
        System.out.println("  sair                     - Encerra a aplicação.");
        System.out.println("\nDados:");
        System.out.println(
            "  dia                      - Consulta o dia atual no servidor."
        );
        System.out.println(
            "  evento <prod> <qtd> <p>  - Regista uma venda de um produto no dia atual."
        );
        System.out.println(
            "  novodia                  - Fecha o dia atual e guarda os dados permanentemente."
        );
        System.out.println(
            "  filtrar <dia> <p1> <p2>  - Lista vendas de certos produtos num dia passado."
        );
        System.out.println("\nEstatísticas (histórico):");
        System.out.println(
            "  qtd <prod> <dias>        - Soma da quantidade vendida nos últimos N dias."
        );
        System.out.println(
            "  vol <prod> <dias>        - Valor financeiro total (qtd * preço) nos últimos N dias."
        );
        System.out.println(
            "  media <prod> <dias>      - Preço médio ponderado nos últimos N dias."
        );
        System.out.println(
            "  max <prod> <dias>        - Preço unitário máximo registado nos últimos N dias."
        );
        System.out.println("\nNotificações (bloqueante):");
        System.out.println(
            "  simul <prod1> <prod2>    - Espera que ambos os produtos sejam vendidos no mesmo dia."
        );
        System.out.println(
            "  consec <N>               - Espera que um produto seja vendido N vezes seguidas."
        );
        System.out.println("--------------------------\n");
    }
}

package server;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ============================================================================
 * GESTOR DE UTILIZADORES - AUTENTICAÇÃO E PERSISTÊNCIA
 * ============================================================================
 *
 * RESPONSABILIDADES:
 * ------------------
 * 1. Registo de novos utilizadores (username único + password)
 * 2. Autenticação de utilizadores existentes
 * 3. Persistência dos dados em disco (sobrevive a reinícios do servidor)
 *
 * MODELO DE SEGURANÇA (SIMPLIFICADO):
 * -----------------------------------
 * ATENÇÃO: Esta implementação é para fins ACADÉMICOS e NÃO deve ser usada em
 * produção! Os problemas de segurança incluem:
 *
 *   1. PASSWORDS EM TEXTO PLANO: Armazenamos passwords diretamente.
 *      Em produção: usar hash + salt (bcrypt, Argon2, PBKDF2)
 *
 *   2. COMPARAÇÃO INSEGURA: equals() é vulnerável a timing attacks.
 *      Em produção: usar MessageDigest.isEqual() ou similar (tempo constante)
 *
 *   3. SEM POLÍTICA DE PASSWORDS: Aceitamos qualquer string.
 *      Em produção: exigir comprimento mínimo, complexidade, etc.
 *
 *   4. SEM RATE LIMITING: Permite brute-force ilimitado.
 *      Em produção: limitar tentativas, implementar backoff, captcha
 *
 *   5. SEM ENCRIPTAÇÃO NO DISCO: Ficheiro de utilizadores é legível.
 *      Em produção: encriptar ficheiro ou usar base de dados segura
 *
 * PORQUÊ NÃO USAR UMA BASE DE DADOS?
 * -----------------------------------
 * Para simplificar o projeto (sem dependências externas como MySQL, PostgreSQL).
 * Ficheiros binários são suficientes para demonstrar os conceitos.
 *
 * Em produção, usaríamos:
 *   - Base de dados relacional (PostgreSQL, MySQL)
 *   - Ou base de dados NoSQL (MongoDB, Redis)
 *   - Com ORM (JPA/Hibernate) para abstração
 *
 * PADRÃO DE CONCORRÊNCIA:
 * -----------------------
 * Múltiplos ClientHandlers podem chamar register() e authenticate()
 * simultaneamente. O lock garante:
 *   - Atomicidade: registo não interfere com autenticação
 *   - Consistência: ficheiro não fica corrompido
 *   - Visibilidade: alterações são vistas por todas as threads
 */
public class UserManager {

    // ==========================================================================
    // ESTRUTURAS DE DADOS
    // ==========================================================================

    /**
     * Mapa de utilizadores: username → password
     *
     * PORQUÊ HashMap E NÃO ConcurrentHashMap?
     * ---------------------------------------
     * ConcurrentHashMap permitiria leituras concorrentes sem lock, mas:
     *   1. Precisamos de atomicidade entre verificar existência e adicionar
     *      (putIfAbsent não é suficiente porque queremos persistir também)
     *   2. A persistência em disco requer lock de qualquer forma
     *   3. Para simplicidade, usamos um lock único para tudo
     *
     * PORQUÊ NÃO TreeMap?
     * -------------------
     * TreeMap mantém ordem (O(log n) para operações), HashMap não (O(1)).
     * Não precisamos de ordem, por isso HashMap é mais eficiente.
     */
    private final Map<String, String> users = new HashMap<>();

    /**
     * Lock para sincronização de todas as operações.
     *
     * GRANULARIDADE DO LOCK:
     * ----------------------
     * Usamos um único lock para tudo (coarse-grained locking).
     *
     * VANTAGENS:
     *   - Simples de implementar e raciocinar
     *   - Impossível ter deadlocks (apenas um lock)
     *   - Correto por construção
     *
     * DESVANTAGENS:
     *   - Menor concorrência (authenticate bloqueia register e vice-versa)
     *   - Pode ser bottleneck se muitos utilizadores
     *
     * ALTERNATIVA (fine-grained):
     * Usar ConcurrentHashMap + lock apenas para escrita em disco.
     * Mas complica o código e não é necessário para a escala deste projeto.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Caminho do ficheiro de persistência.
     * Localizado no diretório "data/" criado pelo Server.java.
     *
     * FORMATO DO FICHEIRO (binário):
     * ------------------------------
     * [4 bytes: número de utilizadores (int)]
     * Para cada utilizador:
     *   [2+ bytes: username (UTF)]
     *   [2+ bytes: password (UTF)]
     *
     * PORQUÊ FORMATO BINÁRIO E NÃO TEXTO (CSV, JSON)?
     * ------------------------------------------------
     * 1. Mais compacto (UTF encoding é eficiente para strings curtas)
     * 2. Mais rápido de ler/escrever (sem parsing de texto)
     * 3. DataInputStream/DataOutputStream simplificam serialização
     * 4. Demonstra uso de streams binárias (objetivo pedagógico)
     *
     * DESVANTAGENS DO BINÁRIO:
     *   - Não legível por humanos
     *   - Versioning mais difícil (adicionar campos quebra formato)
     *   - Debug mais complicado
     *
     * Em produção, JSON ou Protocol Buffers seriam melhores.
     */
    private final String filePath = "data/users.bin";

    // ==========================================================================
    // CONSTRUTOR - INICIALIZAÇÃO E CARREGAMENTO
    // ==========================================================================

    /**
     * Inicializa o gestor e carrega utilizadores persistidos anteriormente.
     *
     * COMPORTAMENTO NO PRIMEIRO ARRANQUE:
     * -----------------------------------
     * Se o ficheiro não existe (primeiro arranque do servidor), o mapa
     * permanece vazio e novos utilizadores podem registar-se.
     *
     * COMPORTAMENTO EM ARRANQUES SUBSEQUENTES:
     * ----------------------------------------
     * Carrega todos os utilizadores do ficheiro para memória.
     * Isto permite autenticação rápida (O(1) lookup no HashMap).
     *
     * TRADE-OFF MEMÓRIA VS DISCO:
     * ---------------------------
     * Carregar tudo para memória:
     *   + Autenticação muito rápida (apenas acesso a HashMap)
     *   - Usa memória proporcional ao número de utilizadores
     *
     * Alternativa (ler do disco a cada autenticação):
     *   + Memória constante
     *   - Muito mais lento (I/O para cada login)
     *
     * Para milhões de utilizadores, usaríamos base de dados com índices.
     */
    public UserManager() {
        loadUsers();
    }

    // ==========================================================================
    // PERSISTÊNCIA - CARREGAR E GUARDAR
    // ==========================================================================

    /**
     * Carrega a base de dados de utilizadores do disco.
     *
     * PROTOCOLO DE LEITURA:
     * ---------------------
     * 1. Verificar se ficheiro existe (se não, é primeiro arranque)
     * 2. Abrir stream binária com buffer
     * 3. Ler número total de utilizadores
     * 4. Para cada utilizador, ler username e password
     * 5. Popular o HashMap
     *
     * TRATAMENTO DE ERROS:
     * --------------------
     * Se o ficheiro estiver corrompido ou incompleto:
     *   - EOFException: ficheiro truncado
     *   - UTFDataFormatException: encoding inválido
     *
     * Nestes casos, imprimimos erro e continuamos com mapa vazio/parcial.
     * Em produção, deveríamos ter:
     *   - Backups automáticos
     *   - Checksums para detetar corrupção
     *   - Modo de recuperação
     */
    private void loadUsers() {
        // Verificar existência do ficheiro
        File f = new File(filePath);
        if (!f.exists()) {
            // Primeiro arranque - nada a carregar
            // O mapa permanece vazio, pronto para novos registos
            return;
        }

        // Usar try-with-resources para garantir fecho da stream
        // mesmo que ocorra exceção
        try (
                // CONSTRUÇÃO DA CADEIA DE STREAMS (de dentro para fora):
                //
                // 1. FileInputStream: lê bytes do ficheiro
                //    - Fornece acesso ao ficheiro no disco
                //    - Leituras são operações de I/O (lentas)
                //
                // 2. BufferedInputStream: adiciona buffer de leitura
                //    - Lê blocos grandes do disco para memória
                //    - Reduz número de chamadas de sistema (syscalls)
                //    - Por defeito, buffer de 8KB
                //    - MELHORIA: Ler 1 byte do ficheiro = ler 8KB para buffer
                //
                // 3. DataInputStream: fornece métodos para tipos primitivos
                //    - readInt(): lê 4 bytes como int
                //    - readUTF(): lê string em formato UTF modificado
                //    - Abstrai detalhes de encoding
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(new FileInputStream(f))
                )
        ) {
            // Ler número total de utilizadores
            int size = in.readInt();

            // Carregar cada utilizador para o HashMap
            for (int i = 0; i < size; i++) {
                // readUTF() lê:
                //   - 2 bytes: comprimento da string (unsigned short)
                //   - N bytes: caracteres em UTF-8 modificado
                String username = in.readUTF();
                String password = in.readUTF();

                // Adicionar ao mapa (sem lock porque estamos no construtor,
                // ainda não há outras threads a aceder)
                users.put(username, password);
            }

            // DEBUG: Log de utilizadores carregados (comentado em produção)
            // System.out.println("[UserManager] Carregados " + size + " utilizadores");

        } catch (IOException e) {
            // Erro ao ler ficheiro - pode ser corrupção ou problema de permissões
            System.err.println("ERRO: Falha ao carregar utilizadores: " + e.getMessage());

            // DECISÃO: Continuar com mapa vazio/parcial
            // ALTERNATIVA: Lançar exceção e impedir arranque do servidor
            // A escolha depende de requisitos de disponibilidade vs consistência

            // NOTA: Se chegámos aqui, o mapa pode ter utilizadores parcialmente
            // carregados. Poderíamos fazer users.clear() para garantir estado limpo.
        }
    }

    /**
     * Persiste o mapa de utilizadores em disco.
     *
     * PROTOCOLO DE ESCRITA:
     * ---------------------
     * 1. Criar/sobrescrever ficheiro
     * 2. Escrever número total de utilizadores
     * 3. Para cada entrada do mapa, escrever username e password
     * 4. Flush e fechar stream
     *
     * ATOMICIDADE DA ESCRITA:
     * -----------------------
     * PROBLEMA: Se o servidor crashar durante a escrita, o ficheiro fica
     * corrompido (parcialmente escrito).
     *
     * SOLUÇÃO ROBUSTA (não implementada por simplicidade):
     * 1. Escrever para ficheiro temporário (users.bin.tmp)
     * 2. Sincronizar para disco (fsync)
     * 3. Renomear atómicamente para ficheiro final (rename é atómico no POSIX)
     * 4. Eliminar temporário se renomeação falhou
     *
     * Esta técnica chama-se "write-ahead" ou "atomic file replacement".
     *
     * QUANDO É CHAMADO:
     * -----------------
     * Apenas em register(), após adicionar utilizador ao mapa.
     * authenticate() não modifica dados, por isso não precisa persistir.
     *
     * PERFORMANCE:
     * ------------
     * Re-escrever todo o ficheiro a cada registo é O(n) onde n = utilizadores.
     * Para muitos utilizadores, seria melhor usar:
     *   - Append-only log (como bases de dados)
     *   - Atualização incremental
     *   - Base de dados real
     */
    private void saveUsers() {
        try (
                // Cadeia de streams similar à leitura, mas para escrita:
                //
                // 1. FileOutputStream: escreve bytes para ficheiro
                //    - Cria ficheiro se não existe
                //    - Sobrescreve se existe (modo truncate por defeito)
                //
                // 2. BufferedOutputStream: buffer de escrita
                //    - Acumula bytes em memória antes de escrever
                //    - Reduz syscalls (write é caro)
                //    - Flush automático no close() ou quando buffer cheio
                //
                // 3. DataOutputStream: métodos para tipos primitivos
                //    - writeInt(): escreve 4 bytes (big-endian)
                //    - writeUTF(): escreve string em UTF modificado
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(new FileOutputStream(filePath))
                )
        ) {
            // Escrever número de utilizadores primeiro
            // Isto permite ao leitor saber quantos registos esperar
            out.writeInt(users.size());

            // Iterar sobre todas as entradas do mapa
            // NOTA: A ordem de iteração de HashMap não é definida,
            // mas isso não importa - apenas precisamos de persistir todos.
            for (Map.Entry<String, String> entry : users.entrySet()) {
                out.writeUTF(entry.getKey());    // username
                out.writeUTF(entry.getValue());  // password
            }

            // O try-with-resources chama close(), que faz flush automaticamente.
            // Em código manual, faríamos: out.flush(); out.close();

        } catch (IOException e) {
            // Erro ao escrever - pode ser disco cheio, sem permissões, etc.
            System.err.println("ERRO: Falha ao persistir utilizadores: " + e.getMessage());

            // PROBLEMA: O registo foi adicionado ao mapa mas não persistido.
            // Se o servidor reiniciar, o utilizador "desaparece".
            //
            // SOLUÇÕES POSSÍVEIS:
            // 1. Reverter: remover do mapa se falhou a persistência
            // 2. Retry: tentar escrever novamente
            // 3. Alertar: notificar administrador
            // 4. Modo degradado: continuar mas marcar como "não persistido"
            //
            // Por simplicidade, apenas logamos o erro e continuamos.
        }
    }

    // ==========================================================================
    // OPERAÇÕES PÚBLICAS - API DO GESTOR
    // ==========================================================================

    /**
     * Regista um novo utilizador no sistema.
     *
     * FLUXO:
     * ------
     * 1. Adquirir lock (exclusão mútua)
     * 2. Verificar se username já existe
     * 3. Se não existe, adicionar ao mapa
     * 4. Persistir em disco
     * 5. Libertar lock
     *
     * ATOMICIDADE:
     * ------------
     * Todo o processo (verificar + adicionar + persistir) é atómico
     * graças ao lock. Não há race conditions do tipo:
     *   Thread A: verifica "alice" não existe
     *   Thread B: verifica "alice" não existe
     *   Thread A: adiciona "alice"
     *   Thread B: adiciona "alice" (SOBRESCREVE!)
     *
     * @param u Nome de utilizador (deve ser único)
     * @param p Palavra-passe (sem requisitos de complexidade nesta versão)
     * @return true se o registo foi bem-sucedido, false se username já existe
     *
     * EXEMPLO DE USO:
     *   if (userManager.register("alice", "password123")) {
     *       System.out.println("Conta criada!");
     *   } else {
     *       System.out.println("Username já existe!");
     *   }
     */
    public boolean register(String u, String p) {
        // PADRÃO: lock(); try { ... } finally { unlock(); }
        // O finally garante que o lock é SEMPRE libertado,
        // mesmo que ocorra exceção dentro do bloco try.
        lock.lock();
        try {
            // Verificar se username já está registado
            // containsKey() é O(1) em HashMap (amortizado)
            if (users.containsKey(u)) {
                // Username já existe - registo falha
                // NÃO revelamos se o username existe ou não por razões de segurança?
                // Na verdade, o retorno booleano revela isso. Em sistemas mais
                // seguros, retornaríamos sempre sucesso e enviaríamos email de
                // confirmação (se email já existe, diria "já tem conta").
                return false;
            }

            // Username disponível - adicionar ao mapa
            users.put(u, p);

            // Persistir imediatamente
            // TRADE-OFF:
            //   - Persistir agora: mais lento, mas durável
            //   - Persistir depois: mais rápido, mas pode perder dados
            // Escolhemos durabilidade sobre performance.
            saveUsers();

            return true;

        } finally {
            // SEMPRE libertar o lock, mesmo que saveUsers() lance exceção
            lock.unlock();
        }
    }

    /**
     * Verifica as credenciais de um utilizador.
     *
     * FLUXO:
     * ------
     * 1. Adquirir lock (para visibilidade consistente do mapa)
     * 2. Procurar username no mapa
     * 3. Comparar password se encontrado
     * 4. Libertar lock
     * 5. Retornar resultado
     *
     * PORQUÊ LOCK SE É APENAS LEITURA?
     * ---------------------------------
     * 1. VISIBILIDADE: Garante que vemos a versão mais recente do mapa
     *    (mesmo que outra thread tenha acabado de registar)
     * 2. CONSISTÊNCIA: Evita ver estado intermédio durante modificação
     * 3. SIMPLICIDADE: Mesmo lock para tudo é mais fácil de raciocinar
     *
     * ALTERNATIVA SEM LOCK:
     * Se usássemos ConcurrentHashMap, leituras não precisariam de lock.
     * Mas perderíamos a garantia de ver registos muito recentes
     * (eventual consistency vs strong consistency).
     *
     * @param u Nome de utilizador
     * @param p Palavra-passe a validar
     * @return true se as credenciais são válidas, false caso contrário
     *
     * NOTA DE SEGURANÇA:
     * ------------------
     * O retorno booleano permite ataques de enumeração de utilizadores:
     *   - Se "alice" retorna "password errada" → alice existe
     *   - Se "bob" retorna "utilizador não existe" → bob não existe
     *
     * Em sistemas seguros, retornaríamos sempre "credenciais inválidas"
     * sem distinguir entre "utilizador não existe" e "password errada".
     * Aqui não fazemos essa distinção (retornamos false em ambos os casos).
     */
    public boolean authenticate(String u, String p) {
        lock.lock();
        try {
            // Procurar password armazenada para este username
            // get() retorna null se username não existe
            String stored = users.get(u);

            // Verificar:
            // 1. O utilizador existe (stored != null)
            // 2. A password corresponde (stored.equals(p))
            //
            // ATENÇÃO: Ordem é importante para evitar NullPointerException
            // Se fizéssemos stored.equals(p) primeiro e stored fosse null, crashava.
            //
            // ALTERNATIVA MAIS SEGURA:
            //   return stored != null && MessageDigest.isEqual(stored.getBytes(), p.getBytes());
            // Isto previne timing attacks (comparação em tempo constante).
            return stored != null && stored.equals(p);

        } finally {
            lock.unlock();
        }
    }
}
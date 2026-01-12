package server;

import common.Protocol;
import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ============================================================================
 * MOTOR DE ARMAZENAMENTO - GESTÃO DE SÉRIES TEMPORAIS
 * ============================================================================
 *
 * CONTEXTO E VISÃO GERAL:
 * -----------------------
 * Este componente é o "coração" do sistema de persistência. Gere todo o ciclo
 * de vida dos dados de vendas: desde a inserção em tempo real até consultas
 * históricas e agregações estatísticas.
 *
 * O desafio principal é balancear três requisitos conflituantes:
 *   1. VELOCIDADE DE ESCRITA: Eventos chegam em tempo real, precisam ser
 *      registados com latência mínima
 *   2. VELOCIDADE DE LEITURA: Consultas históricas devem ser rápidas
 *   3. DURABILIDADE: Dados não podem ser perdidos em caso de crash
 *
 * ARQUITETURA DE ARMAZENAMENTO EM 3 NÍVEIS:
 * -----------------------------------------
 * Inspirada em sistemas como LSM-Trees (Log-Structured Merge Trees), usados
 * em bases de dados como Cassandra, RocksDB e LevelDB:
 *
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │  NÍVEL 1: BUFFER EM MEMÓRIA (currentEvents)                        │
 *   │  ─────────────────────────────────────────────────────────────────  │
 *   │  • Armazena eventos do dia ATUAL                                   │
 *   │  • ArrayList simples - inserção O(1) amortizado                    │
 *   │  • Sem persistência - dados perdidos em crash                      │
 *   │  • MÁXIMA velocidade de escrita                                    │
 *   └────────────────────────────┬────────────────────────────────────────┘
 *                                │ persistDay() - flush para disco
 *                                ▼
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │  NÍVEL 2: CACHE LRU (loadedSeries)                                 │
 *   │  ─────────────────────────────────────────────────────────────────  │
 *   │  • Mantém até S dias mais recentemente acedidos                    │
 *   │  • LinkedHashMap com accessOrder=true                              │
 *   │  • Evita I/O de disco para consultas frequentes                    │
 *   │  • Política LRU: dias menos usados são evictados                   │
 *   └────────────────────────────┬────────────────────────────────────────┘
 *                                │ cache miss - leitura de disco
 *                                ▼
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │  NÍVEL 3: FICHEIROS EM DISCO (data/day_N.dat)                      │
 *   │  ─────────────────────────────────────────────────────────────────  │
 *   │  • Um ficheiro por dia: day_0.dat, day_1.dat, ...                  │
 *   │  • Formato binário compacto (DataOutputStream)                     │
 *   │  • Mantém apenas últimos D dias (janela de retenção)               │
 *   │  • Ficheiros antigos são automaticamente eliminados                │
 *   └─────────────────────────────────────────────────────────────────────┘
 *
 * PORQUÊ ESTA ARQUITETURA?
 * ------------------------
 *
 * 1. BUFFER EM MEMÓRIA:
 *    - Operação addEvent() é O(1) - sem I/O de disco
 *    - Batching implícito: muitos eventos acumulam antes do flush
 *    - Trade-off: se o servidor crashar, perde-se o dia atual
 *    - Em produção: usaríamos Write-Ahead Log (WAL) para durabilidade
 *
 * 2. CACHE LRU:
 *    - Princípio da localidade temporal: dados recentes são mais acedidos
 *    - Parâmetro S controla memória usada vs. hits de cache
 *    - LinkedHashMap com accessOrder reordena automaticamente por acesso
 *    - removeEldestEntry() mantém tamanho limitado
 *
 * 3. FICHEIROS POR DIA:
 *    - Granularidade natural para dados de séries temporais
 *    - Facilita limpeza: basta apagar ficheiro quando sai da janela D
 *    - Leitura sequencial é eficiente (mesmo em HDD)
 *    - Formato binário é mais compacto que texto/JSON
 *
 * CACHE DE AGREGAÇÕES:
 * --------------------
 * Além da cache de dados brutos, mantemos uma cache de resultados de agregações
 * já calculadas (aggCache). Isto é crucial porque:
 *   - Agregações são caras: requerem iterar TODOS os eventos de N dias
 *   - Dados históricos são imutáveis: uma vez calculada, a agregação não muda
 *   - Múltiplos clientes podem pedir a mesma agregação
 *
 *   Exemplo: getAggregation(AGGR_QTY, "Laptop", 30)
 *   - Primeira chamada: itera 30 dias de dados, armazena em aggCache
 *   - Chamadas seguintes: retorna valor da cache instantaneamente
 *
 * FORMATO DE FICHEIRO:
 * --------------------
 * Cada ficheiro day_N.dat contém eventos serializados sequencialmente:
 *
 *   ┌──────────────────────────────────────────────────────────────────┐
 *   │ [UTF produto1][int qty1][double price1]                         │
 *   │ [UTF produto2][int qty2][double price2]                         │
 *   │ [UTF produto3][int qty3][double price3]                         │
 *   │ ...                                                             │
 *   └──────────────────────────────────────────────────────────────────┘
 *
 * Vantagens do formato binário:
 *   - Compacto: int = 4 bytes vs "1000000" = 7 bytes em texto
 *   - Parsing rápido: não há conversão string→número
 *   - DataInputStream/DataOutputStream são otimizados
 *
 * CONCORRÊNCIA:
 * -------------
 * Um único ReentrantLock protege todas as operações. Isto é simples mas
 * conservador. Alternativas mais sofisticadas incluem:
 *   - ReadWriteLock: permite leituras concorrentes
 *   - Lock striping: locks separados por dia
 *   - Lock-free: estruturas como ConcurrentHashMap
 *
 * Para este caso de uso (sistema académico), um lock global é adequado
 * e muito mais fácil de raciocinar sobre correctness.
 *
 * LIMITAÇÕES E MELHORIAS POSSÍVEIS:
 * ---------------------------------
 * 1. DURABILIDADE: Sem WAL, crash perde dia atual
 *    → Solução: escrever log antes de confirmar evento
 *
 * 2. ESCALABILIDADE: Ficheiros grandes são carregados inteiros
 *    → Solução: streaming, índices, ou particionamento
 *
 * 3. CONCORRÊNCIA: Lock global limita throughput
 *    → Solução: locks finos ou estruturas lock-free
 *
 * 4. COMPRESSÃO: Dados não são comprimidos
 *    → Solução: GZIP, LZ4, ou dictionary encoding
 */
public class StorageEngine {

    // ==========================================================================
    // PARÂMETROS DE CONFIGURAÇÃO
    // ==========================================================================

    /**
     * Número máximo de séries (dias) a manter em cache de memória.
     *
     * COMO ESCOLHER O VALOR DE S?
     * ---------------------------
     * Depende de:
     *   - Memória disponível no servidor
     *   - Tamanho médio dos dados por dia
     *   - Padrão de acesso (quantos dias diferentes são consultados)
     *
     * Exemplo de cálculo:
     *   - Média de 10.000 eventos por dia
     *   - Cada evento ≈ 50 bytes (produto + qty + price)
     *   - 10.000 × 50 = 500KB por dia
     *   - Com S=100, cache usa ~50MB de memória
     *
     * Trade-off:
     *   - S alto → mais memória, menos I/O de disco
     *   - S baixo → menos memória, mais leituras de disco
     */
    private final int S;

    /**
     * Janela de retenção em dias - quantos dias de histórico manter.
     *
     * PORQUÊ LIMITAR O HISTÓRICO?
     * ---------------------------
     * 1. ESPAÇO EM DISCO: Dados crescem indefinidamente sem limpeza
     * 2. REQUISITOS DE NEGÓCIO: Análises geralmente focam em períodos recentes
     * 3. COMPLIANCE: Algumas regulamentações exigem remoção de dados antigos
     *
     * Exemplo:
     *   - D=365: mantém 1 ano de histórico
     *   - Dia atual = 400
     *   - Dias [0, 34] são automaticamente eliminados
     *   - Dias [35, 399] estão disponíveis para consulta
     */
    private final int D;

    // ==========================================================================
    // PRIMITIVAS DE SINCRONIZAÇÃO
    // ==========================================================================

    /**
     * Lock global para todas as operações do motor.
     *
     * PORQUÊ UM ÚNICO LOCK?
     * ---------------------
     * Simplicidade: é muito mais fácil garantir correctness com um lock global.
     * Todas as operações que acedem a estado partilhado são mutuamente exclusivas.
     *
     * PORQUÊ ReentrantLock E NÃO synchronized?
     * ----------------------------------------
     * ReentrantLock oferece:
     *   - tryLock() com timeout (não usado aqui, mas disponível)
     *   - lockInterruptibly() para cancelamento
     *   - Conditions mais flexíveis
     *   - Melhor diagnóstico (getOwner(), getQueueLength())
     *
     * Neste caso, synchronized funcionaria igualmente bem.
     *
     * ALTERNATIVA - ReadWriteLock:
     * ----------------------------
     * Se tivéssemos muitas leituras concorrentes, poderíamos usar:
     *   ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
     *   rwLock.readLock().lock();   // múltiplos leitores em paralelo
     *   rwLock.writeLock().lock();  // escritor exclusivo
     *
     * Benefício: leituras não bloqueiam outras leituras
     * Custo: overhead de gestão mais complexa
     */
    private final ReentrantLock lock = new ReentrantLock();

    // ==========================================================================
    // ESTADO DO MOTOR
    // ==========================================================================

    /**
     * Dia atual de operação (contador que começa em 0).
     *
     * SEMÂNTICA:
     * ----------
     * - currentDay = 0: primeiro dia de operação
     * - Incrementado em cada persistDay()
     * - Eventos adicionados vão sempre para o dia currentDay
     * - Consultas podem aceder dias [max(0, currentDay-D), currentDay-1]
     *
     * PERSISTÊNCIA:
     * -------------
     * Este valor é persistido em data/state.bin para sobreviver a restarts.
     * Sem isto, o servidor perderia a noção de qual dia está após reiniciar.
     */
    private int currentDay = 0;

    /**
     * Buffer de eventos do dia atual (ainda não persistidos).
     *
     * PORQUÊ ArrayList?
     * -----------------
     * - Inserção amortizada O(1) no fim
     * - Acesso por índice O(1) se necessário
     * - Iteração eficiente para persistência
     *
     * PORQUÊ NÃO LinkedList?
     * ----------------------
     * - LinkedList tem overhead de memória (nó por elemento)
     * - Pior localidade de cache (nós dispersos na memória)
     * - Não precisamos de inserção/remoção no meio
     *
     * CICLO DE VIDA:
     * --------------
     *   1. addEvent() → adiciona ao fim da lista
     *   2. persistDay() → escreve todos para disco e limpa a lista
     *   3. Repete...
     *
     * NOTA SOBRE DURABILIDADE:
     * ------------------------
     * Se o servidor crashar antes de persistDay(), estes eventos são perdidos!
     * Em sistemas críticos, cada evento seria escrito para um Write-Ahead Log
     * antes de retornar sucesso ao cliente.
     */
    private final List<Sale> currentEvents = new ArrayList<>();

    /**
     * Caminho para o ficheiro de estado persistente.
     * Armazena currentDay e oldestCleanedDay para sobreviver a restarts.
     */
    private final String statePath = "data/state.bin";

    /**
     * Dia mais antigo que já foi limpo (ficheiro eliminado).
     *
     * PORQUÊ GUARDAR ISTO?
     * --------------------
     * Otimização para evitar tentar eliminar ficheiros que já não existem.
     *
     * Sem este campo, em cada persistDay() teríamos de verificar a existência
     * de ficheiros desde o dia 0 até (currentDay - D), o que é ineficiente
     * quando currentDay é grande.
     *
     * Com este campo:
     *   - Só verificamos ficheiros desde oldestCleanedDay até threshold
     *   - Após limpar, atualizamos oldestCleanedDay = threshold
     *   - Próxima limpeza começa onde parou
     */
    private int oldestCleanedDay = 0;

    // ==========================================================================
    // CACHE LRU DE SÉRIES
    // ==========================================================================

    /**
     * Cache que mantém as S séries (dias) mais recentemente acedidas em memória.
     *
     * IMPLEMENTAÇÃO COM LinkedHashMap:
     * --------------------------------
     * LinkedHashMap é uma HashMap que adicionalmente mantém uma lista duplamente
     * ligada de todas as entradas. Com accessOrder=true, a lista é ordenada por
     * ordem de acesso (mais recente no fim).
     *
     * Parâmetros do construtor:
     *   - 16: capacidade inicial da HashMap
     *   - 0.75f: load factor (quando redimensionar)
     *   - true: accessOrder (false seria insertion order)
     *
     * COMO FUNCIONA O LRU:
     * --------------------
     * 1. Cada get() ou put() move a entrada para o FIM da lista
     * 2. A entrada mais ANTIGA (menos recentemente usada) está no INÍCIO
     * 3. removeEldestEntry() é chamado após cada put()
     * 4. Se retornar true, a entrada mais antiga é removida
     *
     * VISUALIZAÇÃO:
     * -------------
     *   Sequência de acessos: get(dia1), get(dia3), get(dia1), get(dia2)
     *
     *   Estado da lista ligada (HEAD → TAIL):
     *     Após get(dia1): [dia1]
     *     Após get(dia3): [dia1, dia3]
     *     Após get(dia1): [dia3, dia1]  ← dia1 movido para o fim
     *     Após get(dia2): [dia3, dia1, dia2]
     *
     *   Se S=2, ao adicionar dia2:
     *     removeEldestEntry() retorna true (size=3 > S=2)
     *     dia3 é removido (está no início)
     *     Resultado: [dia1, dia2]
     *
     * PORQUÊ NÃO USAR Guava Cache OU Caffeine?
     * ----------------------------------------
     * Bibliotecas como Guava Cache ou Caffeine oferecem:
     *   - Evicção automática por tamanho, tempo, ou referências fracas
     *   - Estatísticas de hit/miss
     *   - Loading cache (carrega automaticamente em miss)
     *
     * Para este projeto académico, a implementação manual demonstra
     * melhor compreensão dos conceitos. Em produção, usar Caffeine.
     */
    private final Map<Integer, List<Sale>> loadedSeries = new LinkedHashMap<>(
            16,      // Capacidade inicial - número inicial de buckets
            0.75f,   // Load factor - quando atingir 75% de ocupação, redimensiona
            true     // accessOrder - true para LRU, false para insertion order
    ) {
        /**
         * Método chamado automaticamente após cada put().
         *
         * @param eldest A entrada mais antiga (cabeça da lista ligada)
         * @return true se deve remover a entrada mais antiga
         *
         * LÓGICA:
         * -------
         * Se o tamanho exceder S, removemos a entrada mais antiga.
         * Isto implementa a política LRU automaticamente.
         *
         * NOTA: Este método é chamado ANTES de a nova entrada ser adicionada,
         * por isso usamos > e não >=.
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, List<Sale>> eldest) {
            // Se temos mais de S entradas, remover a mais antiga
            boolean shouldRemove = size() > S;

            // Log opcional para debug (comentado em produção)
            // if (shouldRemove) {
            //     System.out.println("[Cache] Evicting day " + eldest.getKey() + " (size=" + size() + ", S=" + S + ")");
            // }

            return shouldRemove;
        }
    };

    // ==========================================================================
    // CACHE DE AGREGAÇÕES
    // ==========================================================================

    /**
     * Cache de estatísticas agregadas, indexadas por dia e produto.
     *
     * ESTRUTURA:
     * ----------
     *   aggCache: Map<Integer, Map<String, Stats>>
     *             └─ dia ─────► └─ produto ──► Stats(count, vol, max)
     *
     *   Exemplo:
     *     aggCache.get(5).get("Laptop") → Stats{count=100, vol=150000.0, max=2000.0}
     *
     * PORQUÊ CACHEAR AGREGAÇÕES?
     * --------------------------
     * Agregações são CARAS computacionalmente:
     *   - Requerem iterar TODOS os eventos de um dia
     *   - Para aggregate(prod, 30), isso são 30 dias de dados
     *   - Mesmo com 10.000 eventos/dia, são 300.000 iterações
     *
     * Mas dados históricos são IMUTÁVEIS:
     *   - Uma vez que um dia é persistido, seus dados não mudam
     *   - As estatísticas calculadas são válidas para sempre
     *   - Múltiplos clientes podem pedir as mesmas agregações
     *
     * Resultado: calcular uma vez, cachear para sempre.
     *
     * INVALIDAÇÃO:
     * ------------
     * A cache é parcialmente invalidada em persistDay():
     *   - Dias fora da janela D são removidos
     *   - Novos dias ainda não têm cache (calculado sob demanda)
     *
     * NOTA: O dia ATUAL não tem cache porque ainda está a receber eventos.
     * Só dias históricos (< currentDay) são cacheados.
     */
    private final Map<Integer, Map<String, Stats>> aggCache = new HashMap<>();

    // ==========================================================================
    // CONSTRUTOR E INICIALIZAÇÃO
    // ==========================================================================

    /**
     * Inicializa o motor de armazenamento.
     *
     * @param S Número máximo de séries em memória (cache LRU)
     * @param D Janela de retenção em dias
     *
     * FLUXO DE INICIALIZAÇÃO:
     * -----------------------
     *   1. Guardar parâmetros de configuração
     *   2. Carregar estado persistido (currentDay, oldestCleanedDay)
     *   3. Motor pronto para receber eventos
     *
     * NOTA: Não carregamos dados históricos proactivamente.
     * São carregados sob demanda (lazy loading) quando consultados.
     */
    public StorageEngine(int S, int D) {
        this.S = S;  // Tamanho da cache LRU
        this.D = D;  // Janela de retenção

        // Carregar estado anterior se existir
        // Isto permite ao servidor "lembrar" em que dia estava após restart
        loadState();

        // Log de inicialização para debug
        System.out.println("[StorageEngine] Inicializado com S=" + S + ", D=" + D + ", currentDay=" + currentDay);
    }

    // ==========================================================================
    // GESTÃO DE ESTADO PERSISTENTE
    // ==========================================================================

    /**
     * Carrega o estado persistido do ficheiro state.bin.
     *
     * FORMATO DO FICHEIRO:
     * --------------------
     *   [int currentDay]     - 4 bytes
     *   [int oldestCleanedDay] - 4 bytes (opcional, para backwards compatibility)
     *
     * TRATAMENTO DE ERROS:
     * --------------------
     * Se o ficheiro não existir ou estiver corrompido, usamos valores por defeito.
     * Isto permite ao sistema arrancar "limpo" na primeira execução.
     *
     * BACKWARDS COMPATIBILITY:
     * ------------------------
     * Versões antigas podem não ter oldestCleanedDay no ficheiro.
     * Verificamos available() antes de ler para evitar EOFException.
     */
    private void loadState() {
        File f = new File(statePath);

        // Se não existe ficheiro de estado, é primeira execução
        if (!f.exists()) {
            System.out.println("[StorageEngine] Sem estado anterior - iniciando do dia 0");
            return;
        }

        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            // Ler dia atual
            this.currentDay = in.readInt();

            // Tentar ler oldestCleanedDay (pode não existir em ficheiros antigos)
            if (in.available() >= 4) {
                this.oldestCleanedDay = in.readInt();
            } else {
                // Ficheiro antigo sem este campo - calcular valor
                this.oldestCleanedDay = Math.max(0, currentDay - D);
            }

            System.out.println("[StorageEngine] Estado carregado: day=" + currentDay + ", oldestCleaned=" + oldestCleanedDay);

        } catch (IOException e) {
            // Ficheiro corrompido ou erro de I/O
            // Resetar para estado inicial é mais seguro que crashar
            System.err.println("AVISO: Falha ao carregar estado do motor: " + e.getMessage());
            System.err.println("       A iniciar com valores por defeito.");
            this.currentDay = 0;
            this.oldestCleanedDay = 0;
        }
    }

    /**
     * Persiste o estado atual em disco.
     *
     * QUANDO É CHAMADO:
     * -----------------
     * - Em cada persistDay() após avançar o contador
     * - Garante que restart do servidor mantém continuidade
     *
     * ATOMICIDADE:
     * ------------
     * PROBLEMA: Se o servidor crashar durante a escrita, o ficheiro pode
     * ficar corrompido (parcialmente escrito).
     *
     * SOLUÇÃO ROBUSTA (não implementada aqui):
     *   1. Escrever para ficheiro temporário (state.bin.tmp)
     *   2. Fazer fsync() para garantir que está em disco
     *   3. Renomear atomicamente para state.bin
     *   4. Apagar temporário se existir
     *
     * O rename é atómico na maioria dos filesystems, garantindo que
     * state.bin está sempre completo ou não existe.
     */
    private void saveState() {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(statePath))) {
            out.writeInt(currentDay);
            out.writeInt(oldestCleanedDay);
            // NOTA: Não fazemos out.flush() explícito porque close() faz flush
        } catch (IOException e) {
            // Log erro mas não propagar - não queremos falhar operações de negócio
            // por causa de falha em persistir estado auxiliar
            System.err.println("ERRO: Falha ao salvar estado do motor: " + e.getMessage());
        }
    }

    // ==========================================================================
    // OPERAÇÕES PÚBLICAS - CONSULTA
    // ==========================================================================

    /**
     * Retorna o dia atual de operação.
     *
     * @return Número do dia corrente (0-indexed)
     *
     * USO:
     * ----
     * - Clientes podem consultar em que dia o servidor está
     * - Usado para validar parâmetros de consulta (dia deve ser < currentDay)
     * - Interface simples de leitura de estado
     *
     * THREAD-SAFETY:
     * --------------
     * Mesmo sendo apenas leitura, adquirimos o lock para garantir que
     * vemos um valor consistente e não apanhamos uma escrita parcial.
     *
     * NOTA: Em Java, int é atómico (lido/escrito em uma operação),
     * então tecnicamente o lock não seria necessário para consistência.
     * Mas por princípio, protegemos todo o acesso a estado partilhado.
     */
    public int getCurrentDay() {
        lock.lock();
        try {
            return this.currentDay;
        } finally {
            // SEMPRE libertar o lock, mesmo em caso de excepção
            // O padrão try-finally garante isto
            lock.unlock();
        }
    }

    // ==========================================================================
    // OPERAÇÕES PÚBLICAS - ESCRITA
    // ==========================================================================

    /**
     * Adiciona um evento de venda ao buffer do dia atual.
     *
     * @param p  Identificador do produto (ex: "Laptop", "iPhone15")
     * @param q  Quantidade vendida (deve ser > 0)
     * @param pr Preço unitário (deve ser > 0)
     *
     * FLUXO:
     * ------
     *   1. Adquirir lock
     *   2. Criar objeto Sale imutável
     *   3. Adicionar ao buffer currentEvents
     *   4. Libertar lock
     *
     * COMPLEXIDADE: O(1) amortizado (ArrayList.add no fim)
     *
     * DURABILIDADE:
     * -------------
     * O evento NÃO é persistido em disco neste momento!
     * Fica no buffer em memória até persistDay() ser chamado.
     *
     * Se o servidor crashar antes de persistDay():
     *   - Todos os eventos do dia atual são perdidos
     *   - Cliente recebeu "sucesso" mas dados não foram guardados
     *
     * SOLUÇÃO PARA SISTEMAS CRÍTICOS:
     * -------------------------------
     * Write-Ahead Log (WAL):
     *   1. Escrever evento para ficheiro de log (append-only)
     *   2. Fazer fsync() para garantir durabilidade
     *   3. Só então adicionar ao buffer e retornar sucesso
     *   4. Em crash recovery, replay do log reconstrói o buffer
     *
     * Trade-off: WAL adiciona latência (I/O síncrono por evento)
     *
     * VALIDAÇÃO:
     * ----------
     * Esta implementação não valida parâmetros (qty > 0, price > 0).
     * Em produção, deveríamos:
     *   - Validar e lançar IllegalArgumentException se inválido
     *   - Ou retornar boolean/Result indicando sucesso/falha
     */
    public void addEvent(String p, int q, double pr) {
        lock.lock();
        try {
            // Criar objeto imutável representando a venda
            Sale sale = new Sale(p, q, pr);

            // Adicionar ao buffer do dia atual
            // ArrayList.add() é O(1) amortizado (ocasionalmente O(n) para resize)
            currentEvents.add(sale);

            // Log opcional para debug de alto volume
            // System.out.println("[StorageEngine] Evento: " + p + ", qty=" + q + ", price=" + pr);

        } finally {
            lock.unlock();
        }
    }

    /**
     * Persiste os eventos do dia atual e avança para o próximo dia.
     *
     * @throws IOException Se ocorrer erro de escrita em disco
     *
     * FLUXO DETALHADO:
     * ----------------
     *   1. Adquirir lock (operação crítica)
     *   2. Criar ficheiro day_N.dat
     *   3. Escrever todos os eventos do buffer
     *   4. Limpar buffer currentEvents
     *   5. Incrementar currentDay
     *   6. Persistir estado (currentDay, oldestCleanedDay)
     *   7. Limpar caches de dias fora da janela
     *   8. Eliminar ficheiros antigos
     *   9. Libertar lock
     *
     * ATOMICIDADE:
     * ------------
     * Esta operação não é verdadeiramente atómica. Se o servidor crashar:
     *   - Durante escrita: ficheiro parcial no disco
     *   - Após escrita, antes de limpar buffer: dados duplicados em restart
     *   - Após limpar buffer, antes de incrementar day: dia perdido
     *
     * SOLUÇÃO ROBUSTA (não implementada):
     * -----------------------------------
     *   1. Escrever para ficheiro temporário (day_N.dat.tmp)
     *   2. fsync() para garantir dados em disco
     *   3. Renomear atomicamente para day_N.dat
     *   4. Atualizar estado e buffer numa "transação"
     *   5. Em recovery: se .tmp existe, verificar integridade e finalizar
     *
     * PORQUÊ BufferedOutputStream?
     * ----------------------------
     * DataOutputStream escreve diretamente para o ficheiro, causando
     * uma syscall por cada writeUTF/writeInt/writeDouble.
     *
     * BufferedOutputStream acumula dados em memória e faz escritas maiores,
     * reduzindo dramaticamente o número de syscalls.
     *
     * Exemplo:
     *   - 10.000 eventos × 3 campos = 30.000 syscalls sem buffer
     *   - Com buffer de 8KB: ~dezenas de syscalls
     */
    public void persistDay() throws IOException {
        lock.lock();
        try {
            // Construir caminho do ficheiro para este dia
            // Formato: data/day_0.dat, data/day_1.dat, ...
            File f = new File("data/day_" + currentDay + ".dat");

            // Abrir stream para escrita binária com buffering
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(f)))) {

                // Escrever cada evento sequencialmente
                for (Sale s : currentEvents) {
                    // writeUTF: escreve comprimento (2 bytes) + caracteres UTF-8
                    // writeInt: escreve 4 bytes em big-endian
                    // writeDouble: escreve 8 bytes (IEEE 754)
                    out.writeUTF(s.prod);
                    out.writeInt(s.qty);
                    out.writeDouble(s.price);
                }

                // NOTA: out.close() faz flush automático
            }

            // Log de persistência
            int eventCount = currentEvents.size();
            System.out.println("[StorageEngine] Dia " + currentDay + " persistido com " + eventCount + " eventos");

            // Limpar buffer - prontos para receber eventos do novo dia
            currentEvents.clear();

            // Avançar para o próximo dia
            currentDay++;

            // Persistir estado atualizado
            saveState();

            // Calcular threshold para limpeza (dias abaixo deste são antigos)
            int threshold = currentDay - D;

            // Limpar caches de dias fora da janela de retenção
            // removeIf é eficiente - itera uma vez e remove in-place
            aggCache.keySet().removeIf(day -> day < threshold);
            loadedSeries.keySet().removeIf(day -> day < threshold);

            // Eliminar ficheiros de dias antigos
            cleanupOldFiles(threshold);

        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove ficheiros de dias fora da janela de retenção.
     *
     * @param threshold Dias com índice < threshold são eliminados
     *
     * OTIMIZAÇÃO:
     * -----------
     * Mantemos oldestCleanedDay para saber onde começar a verificar.
     * Sem isto, teríamos de verificar desde o dia 0 em cada chamada.
     *
     * Exemplo:
     *   - currentDay=400, D=365, threshold=35
     *   - oldestCleanedDay=30
     *   - Só verificamos dias [30, 35), não [0, 35)
     *
     * TOLERÂNCIA A FALHAS:
     * --------------------
     * Se delete() falhar (ficheiro em uso, permissões, etc.), apenas
     * logamos um aviso e continuamos. Não queremos falhar persistDay()
     * por causa de limpeza.
     */
    private void cleanupOldFiles(int threshold) {
        // Se threshold não avançou, nada a fazer
        if (threshold <= oldestCleanedDay) {
            return;
        }

        // Iterar desde o último dia limpo até ao novo threshold
        for (int day = oldestCleanedDay; day < threshold; day++) {
            File oldFile = new File("data/day_" + day + ".dat");

            if (oldFile.exists()) {
                if (oldFile.delete()) {
                    // Log opcional de limpeza
                    // System.out.println("[StorageEngine] Ficheiro antigo eliminado: " + oldFile.getName());
                } else {
                    // Falha na eliminação - logar mas continuar
                    System.err.println("AVISO: Não foi possível eliminar ficheiro antigo: " + oldFile.getName());
                }
            }
            // Se o ficheiro não existe, pode já ter sido eliminado anteriormente
            // ou nunca ter existido (gap nos dias)
        }

        // Atualizar marcador para próxima limpeza
        oldestCleanedDay = threshold;
    }

    // ==========================================================================
    // OPERAÇÕES PÚBLICAS - AGREGAÇÃO
    // ==========================================================================

    /**
     * Calcula uma agregação estatística sobre dados históricos.
     *
     * @param type Tipo de agregação:
     *             - Protocol.AGGR_QTY: soma de quantidades vendidas
     *             - Protocol.AGGR_VOL: soma de (quantidade × preço)
     *             - Protocol.AGGR_AVG: preço médio ponderado por quantidade
     *             - Protocol.AGGR_MAX: preço unitário máximo
     * @param prod Produto a analisar (ex: "Laptop")
     * @param d    Número de dias retroativos a considerar
     * @return Valor calculado da agregação
     * @throws IOException Se ocorrer erro ao ler dados do disco
     *
     * EXEMPLO DE USO:
     * ---------------
     *   aggregate(Protocol.AGGR_QTY, "Laptop", 30)
     *   → Soma de todas as quantidades de Laptop vendidas nos últimos 30 dias
     *
     *   aggregate(Protocol.AGGR_AVG, "Laptop", 30)
     *   → Preço médio ponderado: (soma de qty*price) / (soma de qty)
     *
     * ALGORITMO:
     * ----------
     *   1. Determinar quantos dias processar (mín de d, D, currentDay)
     *   2. Para cada dia no intervalo:
     *      a. Obter estatísticas do dia (da cache ou calculando)
     *      b. Acumular count, volume, max
     *   3. Calcular resultado final conforme tipo de agregação
     *
     * OTIMIZAÇÃO - CACHE DE ESTATÍSTICAS:
     * -----------------------------------
     * Não iteramos os eventos brutos de cada dia. Em vez disso:
     *   1. getOrComputeStats(dia, produto) verifica se já temos Stats na cache
     *   2. Se não, calcula a partir dos eventos e guarda na cache
     *   3. Da próxima vez, retorna da cache (O(1) vs O(n))
     *
     * Isto é possível porque dados históricos são IMUTÁVEIS.
     * Uma vez que um dia é persistido, suas estatísticas não mudam.
     *
     * VALIDAÇÃO:
     * ----------
     * O parâmetro 'd' é validado no ClientHandler antes de chamar este método.
     * Aqui fazemos apenas clamp para valores válidos, por segurança adicional.
     */
    public double aggregate(int type, String prod, int d) throws IOException {
        lock.lock();
        try {
            // Acumuladores para as estatísticas
            int totalCount = 0;      // Soma de quantidades
            double totalVol = 0;     // Soma de (qty × price)
            double globalMax = 0;    // Máximo de preços

            // Calcular quantos dias realmente processar
            // - d: dias pedidos pelo cliente
            // - D: janela de retenção configurada
            // - currentDay: dias que realmente existem
            // Usamos o mínimo dos três para não aceder dados inexistentes
            int daysToProcess = Math.min(d, Math.min(D, currentDay));

            // Iterar dias do mais recente para o mais antigo
            // i=1 significa "ontem", i=2 significa "anteontem", etc.
            // Não incluímos o dia atual porque ainda não foi persistido
            for (int i = 1; i <= daysToProcess; i++) {
                int target = currentDay - i;  // Dia absoluto a processar

                // Saltar dias negativos (impossível, mas por segurança)
                if (target < 0) {
                    continue;
                }

                // Obter estatísticas para este dia/produto
                // Esta chamada usa a cache ou calcula se necessário
                Stats s = getOrComputeStats(target, prod);

                // Acumular valores
                totalCount += s.count;
                totalVol += s.vol;
                globalMax = Math.max(globalMax, s.max);
            }

            // Calcular resultado final baseado no tipo de agregação
            // Usamos switch expression (Java 14+) para código mais limpo
            return switch (type) {
                case Protocol.AGGR_QTY -> totalCount;
                // Quantidade total: soma de todas as quantidades

                case Protocol.AGGR_VOL -> totalVol;
                // Volume financeiro: soma de (qty × price) para cada venda

                case Protocol.AGGR_AVG -> totalCount == 0 ? 0 : totalVol / totalCount;
                // Média ponderada: volume / quantidade
                // Proteger contra divisão por zero se não há vendas

                case Protocol.AGGR_MAX -> globalMax;
                // Preço máximo: maior preço unitário encontrado

                default -> 0;
                // Tipo desconhecido - retornar 0 (defensivo)
            };

        } finally {
            lock.unlock();
        }
    }

    /**
     * Obtém estatísticas de um dia/produto, usando cache se disponível.
     *
     * @param day  Dia a processar (deve ser < currentDay)
     * @param prod Produto a analisar
     * @return Estatísticas calculadas (count, vol, max)
     * @throws IOException Se ocorrer erro ao ler dados do disco
     *
     * PADRÃO CACHE-ASIDE:
     * -------------------
     *   1. Verificar se resultado está na cache
     *   2. Se sim: retornar imediatamente (cache hit)
     *   3. Se não: calcular, armazenar na cache, retornar (cache miss)
     *
     * Este é um padrão comum em sistemas de cache, às vezes chamado
     * "lazy population" ou "read-through caching".
     *
     * ESTRUTURA DA CACHE:
     * -------------------
     *   aggCache: {
     *     5: {                          // dia 5
     *       "Laptop": Stats{100, 150000, 2000},
     *       "iPhone": Stats{200, 300000, 1500}
     *     },
     *     6: {                          // dia 6
     *       "Laptop": Stats{50, 75000, 1800}
     *     }
     *   }
     *
     * EFICIÊNCIA:
     * -----------
     * - Primeira consulta para dia/produto: O(n) onde n = eventos do dia
     * - Consultas subsequentes: O(1) (lookup em HashMap)
     *
     * INVALIDAÇÃO:
     * ------------
     * A cache é invalidada parcialmente em persistDay():
     *   - Dias fora da janela D são removidos
     *   - Isto liberta memória e mantém cache coerente
     */
    private Stats getOrComputeStats(int day, String prod) throws IOException {
        // Garantir que existe mapa para este dia
        // putIfAbsent é atómico e evita sobrescrever se já existe
        aggCache.putIfAbsent(day, new HashMap<>());

        // Obter mapa de produtos para este dia
        Map<String, Stats> dayCache = aggCache.get(day);

        // Verificar se já temos estatísticas para este produto
        if (dayCache.containsKey(prod)) {
            // CACHE HIT - retornar imediatamente
            return dayCache.get(prod);
        }

        // CACHE MISS - calcular estatísticas
        Stats res = new Stats();

        // Carregar eventos do dia (também usa cache LRU)
        List<Sale> events = fetchDayEvents(day);

        // Iterar todos os eventos, filtrando pelo produto
        for (Sale s : events) {
            if (s.prod.equals(prod)) {
                // Acumular estatísticas
                res.count += s.qty;                    // Soma de quantidades
                res.vol += (s.qty * s.price);          // Soma de (qty × price)
                res.max = Math.max(res.max, s.price);  // Máximo de preços
            }
        }

        // Armazenar na cache para consultas futuras
        dayCache.put(prod, res);

        return res;
    }

    // ==========================================================================
    // OPERAÇÕES INTERNAS - GESTÃO DE CACHE
    // ==========================================================================

    /**
     * Carrega eventos de um dia, utilizando a cache LRU se disponível.
     *
     * @param day Dia a carregar
     * @return Lista de eventos do dia (pode estar vazia se dia não existe)
     * @throws IOException Se ocorrer erro de leitura
     *
     * FLUXO:
     * ------
     *   1. Verificar se dia está na cache LRU (loadedSeries)
     *   2. Se sim: retornar da cache (cache hit)
     *   3. Se não: carregar ficheiro do disco, colocar na cache, retornar
     *
     * A colocação na cache pode causar evicção da entrada menos
     * recentemente usada se exceder o limite S.
     *
     * NOTA: Esta é a cache de DADOS BRUTOS, diferente da cache de
     * ESTATÍSTICAS AGREGADAS (aggCache). Ambas são úteis:
     *   - loadedSeries: evita I/O quando precisamos iterar eventos
     *   - aggCache: evita recalcular estatísticas já conhecidas
     */
    private List<Sale> fetchDayEvents(int day) throws IOException {
        // Verificar cache LRU
        if (loadedSeries.containsKey(day)) {
            // CACHE HIT
            // NOTA: get() em LinkedHashMap com accessOrder=true
            // também atualiza a posição na lista LRU
            return loadedSeries.get(day);
        }

        // CACHE MISS - carregar do disco
        File f = new File("data/day_" + day + ".dat");

        // Se ficheiro não existe, retornar lista vazia
        // Isto pode acontecer se o dia foi eliminado ou nunca existiu
        if (!f.exists()) {
            return new ArrayList<>();
        }

        // Carregar ficheiro
        List<Sale> list = loadFile(f);

        // Colocar na cache LRU
        // Se cache está cheia (size > S), removeEldestEntry() remove o mais antigo
        loadedSeries.put(day, list);

        return list;
    }

    /**
     * Lê um ficheiro de dados do disco e reconstrói a lista de eventos.
     *
     * @param f Ficheiro a ler
     * @return Lista de eventos reconstruídos
     * @throws IOException Se ocorrer erro de leitura
     *
     * FORMATO DO FICHEIRO:
     * --------------------
     * Sequência de registos sem delimitador ou contagem:
     *   [UTF produto][int qty][double price]
     *   [UTF produto][int qty][double price]
     *   ...
     *
     * Lemos até EOF (in.available() == 0).
     *
     * PORQUÊ BufferedInputStream?
     * ---------------------------
     * Tal como na escrita, o buffering reduz syscalls de leitura.
     * FileInputStream faz uma syscall por cada read(), enquanto
     * BufferedInputStream lê blocos maiores para um buffer interno.
     *
     * NOTA SOBRE INTEGRIDADE:
     * -----------------------
     * Se o ficheiro estiver corrompido (escrita parcial, disco danificado),
     * readUTF/readInt/readDouble podem lançar exceções ou ler dados incorretos.
     *
     * Em sistemas críticos, usaríamos:
     *   - Checksums para detetar corrupção
     *   - Marcadores de registo para encontrar limites válidos
     *   - Ficheiros de redundância ou replicação
     */
    private List<Sale> loadFile(File f) throws IOException {
        List<Sale> l = new ArrayList<>();

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(f)))) {

            // Ler enquanto houver dados
            // available() retorna estimativa de bytes legíveis sem bloquear
            while (in.available() > 0) {
                // Reconstruir evento a partir dos campos serializados
                String prod = in.readUTF();
                int qty = in.readInt();
                double price = in.readDouble();

                l.add(new Sale(prod, qty, price));
            }
        }

        return l;
    }

    // ==========================================================================
    // OPERAÇÕES PÚBLICAS - FILTRAGEM
    // ==========================================================================

    /**
     * Filtra eventos de um dia específico por conjunto de produtos.
     *
     * @param day    Dia a consultar (deve ser < currentDay)
     * @param filter Conjunto de produtos a incluir no resultado
     * @return Lista de eventos que correspondem aos critérios
     * @throws IOException Se ocorrer erro de leitura
     *
     * EXEMPLO DE USO:
     * ---------------
     *   getEventsForDay(5, Set.of("Laptop", "iPhone"))
     *   → Todas as vendas de Laptop ou iPhone no dia 5
     *
     * ALGORITMO:
     * ----------
     *   1. Validar que dia é válido (histórico, dentro da janela)
     *   2. Carregar eventos do dia (usa cache LRU)
     *   3. Filtrar por pertença ao conjunto de produtos
     *   4. Retornar lista filtrada
     *
     * COMPLEXIDADE: O(n) onde n = eventos do dia
     *
     * VALIDAÇÃO:
     * ----------
     * Lança IllegalArgumentException se:
     *   - day < 0 (dia negativo)
     *   - day >= currentDay (dia atual ou futuro não persistido)
     *
     * NOTA: A validação de janela D é feita no ClientHandler, não aqui.
     * Este método assume que day está dentro da janela válida.
     */
    public List<Sale> getEventsForDay(int day, Set<String> filter)
            throws IOException {
        lock.lock();
        try {
            // Validação de intervalo
            if (day < 0 || day >= currentDay) {
                throw new IllegalArgumentException(
                        "Dia inválido: " + day + ". Intervalo válido: [0, " + (currentDay - 1) + "]"
                );
            }

            // Carregar eventos do dia (usa cache LRU)
            List<Sale> all = fetchDayEvents(day);

            // Filtrar eventos pelo conjunto de produtos
            List<Sale> filtered = new ArrayList<>();
            for (Sale s : all) {
                // Set.contains() é O(1) para HashSet
                if (filter.contains(s.prod)) {
                    filtered.add(s);
                }
            }

            return filtered;

        } finally {
            lock.unlock();
        }
    }

    // ==========================================================================
    // CLASSES INTERNAS - ESTRUTURAS DE DADOS
    // ==========================================================================

    /**
     * Representação imutável de um evento de venda.
     *
     * IMUTABILIDADE:
     * --------------
     * Todos os campos são final, garantindo que uma Sale não pode ser
     * modificada após criação. Isto traz várias vantagens:
     *
     *   1. THREAD-SAFETY: Objetos imutáveis são automaticamente thread-safe
     *      Podem ser partilhados entre threads sem sincronização
     *
     *   2. SIMPLICIDADE: Não há estados inválidos ou transições a gerir
     *      O objeto é válido desde a construção até ser garbage collected
     *
     *   3. CACHE: Objetos imutáveis podem ser cacheados com segurança
     *      Sabemos que o valor não vai mudar
     *
     *   4. DEFENSIVA: Código que recebe uma Sale não pode corrompê-la
     *      Não precisa de fazer cópias defensivas
     *
     * ALTERNATIVA - RECORD:
     * ---------------------
     * Em Java 16+, poderíamos usar:
     *   public record Sale(String prod, int qty, double price) {}
     *
     * Records são automaticamente imutáveis e geram:
     *   - Construtor canónico
     *   - Getters (prod(), qty(), price())
     *   - equals(), hashCode(), toString()
     *
     * Usamos classe tradicional para compatibilidade com Java mais antigo.
     *
     * SERIALIZAÇÃO:
     * -------------
     * Esta classe não implementa Serializable porque usamos serialização
     * manual com DataOutputStream. Se quiséssemos Java serialization:
     *
     *   public static class Sale implements Serializable {
     *       private static final long serialVersionUID = 1L;
     *       ...
     *   }
     */
    public static class Sale {

        /**
         * Identificador do produto vendido.
         * Exemplos: "Laptop", "iPhone15", "PS5"
         */
        public final String prod;

        /**
         * Quantidade vendida nesta transação.
         * Deve ser > 0 (não validado aqui, assume-se válido).
         */
        public final int qty;

        /**
         * Preço unitário em moeda base (ex: euros).
         * Deve ser > 0 (não validado aqui, assume-se válido).
         */
        public final double price;

        /**
         * Cria um novo evento de venda imutável.
         *
         * @param p  Identificador do produto
         * @param q  Quantidade vendida
         * @param pr Preço unitário
         *
         * NOTA: Não fazemos cópia defensiva de 'p' porque String é imutável.
         * Se prod fosse um tipo mutável, deveríamos fazer:
         *   this.prod = new MutableType(p);
         * Para evitar que o chamador modifique o estado interno.
         */
        public Sale(String p, int q, double pr) {
            this.prod = p;
            this.qty = q;
            this.price = pr;
        }

        /**
         * Representação textual para debug.
         * Não é usada em produção, mas útil durante desenvolvimento.
         */
        @Override
        public String toString() {
            return "Sale{prod='" + prod + "', qty=" + qty + ", price=" + price + '}';
        }
    }

    /**
     * Contentor para estatísticas agregadas de um produto num dia.
     *
     * DESIGN:
     * -------
     * Esta classe é mutável e usada apenas internamente para acumular
     * valores durante o cálculo de agregações. Não é exposta na API pública.
     *
     * Por ser interna e usada dentro de locks, a mutabilidade não causa
     * problemas de concorrência.
     *
     * CAMPOS:
     * -------
     * - count: Soma de todas as quantidades vendidas
     *          Útil para: AGGR_QTY, AGGR_AVG
     *
     * - vol: Soma de (quantidade × preço) para cada venda
     *        Útil para: AGGR_VOL, AGGR_AVG
     *
     * - max: Maior preço unitário encontrado
     *        Útil para: AGGR_MAX
     *
     * EXEMPLO:
     * --------
     *   Vendas de "Laptop" no dia 5:
     *     - 10 unidades a 1000€ cada
     *     - 5 unidades a 1200€ cada
     *     - 3 unidades a 900€ cada
     *
     *   Stats resultante:
     *     count = 10 + 5 + 3 = 18
     *     vol = (10×1000) + (5×1200) + (3×900) = 18700
     *     max = max(1000, 1200, 900) = 1200
     *
     *   Agregações:
     *     AGGR_QTY = 18
     *     AGGR_VOL = 18700
     *     AGGR_AVG = 18700 / 18 ≈ 1038.89
     *     AGGR_MAX = 1200
     */
    static class Stats {
        /** Quantidade total vendida (soma de qty). */
        int count = 0;

        /** Volume financeiro total (soma de qty × price). */
        double vol = 0;

        /** Preço máximo encontrado. */
        double max = 0;

        /**
         * Representação textual para debug.
         */
        @Override
        public String toString() {
            return "Stats{count=" + count + ", vol=" + vol + ", max=" + max + '}';
        }
    }
}
package server;

import common.Protocol;
import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Motor de armazenamento especializado em séries temporais com gestão de memória.
 * Suporta grandes volumes de dados através de persistência em ficheiro, cache LRU
 * para séries frequentes e cache preguiçosa para resultados agregados. Preserva
 * o contador do dia atual entre reinicializações.
 *
 * NOTA sobre streaming (Requisito 7 do enunciado):
 * A implementação atual usa uma cache LRU que mantém no máximo S séries em memória.
 * Para datasets extremamente grandes (biliões de eventos por dia), seria necessário
 * implementar processamento em streaming onde os eventos são lidos, processados e
 * descartados incrementalmente sem carregar todo o ficheiro em memória.
 * A implementação atual é adequada para a maioria dos casos de uso práticos.
 */
public class StorageEngine {

    private final int S;
    private final int D;
    private final ReentrantLock lock = new ReentrantLock();
    private int currentDay = 0;
    private final List<Sale> currentEvents = new ArrayList<>();
    private final String statePath = "data/state.bin";

    /** Rastreia o dia mais antigo que já foi limpo, para evitar iterações desnecessárias. */
    private int oldestCleanedDay = 0;

    /**
     * Cache LRU (Least Recently Used) que mantém apenas as S séries mais
     * recentemente acedidas em memória. Quando o limite S é atingido, a série
     * menos recentemente utilizada é automaticamente removida para dar lugar a uma nova.
     */
    private final Map<Integer, List<Sale>> loadedSeries = new LinkedHashMap<>(
            16,
            0.75f,
            true
    ) {
        @Override
        protected boolean removeEldestEntry(
                Map.Entry<Integer, List<Sale>> eldest
        ) {
            return size() > S;
        }
    };

    /** Cache para guardar agregações já calculadas, indexadas por dia e produto. */
    private final Map<Integer, Map<String, Stats>> aggCache = new HashMap<>();

    /**
     * @param S Número máximo de séries (ficheiros) em memória RAM.
     * @param D Janela de retenção de dias históricos.
     */
    public StorageEngine(int S, int D) {
        this.S = S;
        this.D = D;
        loadState();
    }

    /**
     * Carrega o estado global do motor de armazenamento (dia atual) do disco.
     * Evita sobreposição de ficheiros de dados após um restart do servidor.
     */
    private void loadState() {
        File f = new File(statePath);
        if (!f.exists()) return;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            this.currentDay = in.readInt();
            // Carrega o dia mais antigo limpo, se existir (compatibilidade)
            if (in.available() >= 4) {
                this.oldestCleanedDay = in.readInt();
            } else {
                // Servidor antigo, calcula baseado no dia atual
                this.oldestCleanedDay = Math.max(0, currentDay - D);
            }
        } catch (IOException e) {
            System.err.println(
                    "ERRO: Falha ao carregar estado do motor: " + e.getMessage()
            );
        }
    }

    /**
     * Persiste o contador do dia atual no sistema de ficheiros.
     */
    private void saveState() {
        try (
                DataOutputStream out = new DataOutputStream(
                        new FileOutputStream(statePath)
                )
        ) {
            out.writeInt(currentDay);
            out.writeInt(oldestCleanedDay);
        } catch (IOException e) {
            System.err.println(
                    "ERRO: Falha ao salvar estado do motor: " + e.getMessage()
            );
        }
    }

    /**
     * Retorna o dia atual de operação do servidor.
     * @return O contador do dia corrente.
     */
    public int getCurrentDay() {
        lock.lock();
        try {
            return this.currentDay;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adiciona um evento ao buffer do dia atual (que reside sempre em RAM).
     * @param p Produto.
     * @param q Quantidade.
     * @param pr Preço.
     */
    public void addEvent(String p, int q, double pr) {
        lock.lock();
        try {
            currentEvents.add(new Sale(p, q, pr));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Escreve os eventos acumulados do dia em disco, limpa dados obsoletos,
     * e atualiza o estado persistente. Incrementa o contador de dias.
     * @throws IOException Se falhar a escrita física.
     */
    public void persistDay() throws IOException {
        lock.lock();
        try {
            File f = new File("data/day_" + currentDay + ".dat");
            try (
                    DataOutputStream out = new DataOutputStream(
                            new BufferedOutputStream(new FileOutputStream(f))
                    )
            ) {
                for (Sale s : currentEvents) {
                    out.writeUTF(s.prod);
                    out.writeInt(s.qty);
                    out.writeDouble(s.price);
                }
            }
            currentEvents.clear();
            currentDay++;
            saveState();

            // Remove proativamente dados fora da janela de interesse (D)
            int threshold = currentDay - D;
            aggCache.keySet().removeIf(day -> day < threshold);
            loadedSeries.keySet().removeIf(day -> day < threshold);

            // Limpa ficheiros antigos de forma eficiente
            cleanupOldFiles(threshold);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove ficheiros de dias que estão fora da janela de retenção.
     * Otimizado para apenas verificar ficheiros que ainda não foram limpos.
     * @param threshold Dias abaixo deste valor devem ser removidos.
     */
    private void cleanupOldFiles(int threshold) {
        if (threshold <= oldestCleanedDay) return;

        // Apenas itera sobre os dias que ainda não foram limpos
        for (int day = oldestCleanedDay; day < threshold; day++) {
            File oldFile = new File("data/day_" + day + ".dat");
            if (oldFile.exists()) {
                if (!oldFile.delete()) {
                    System.err.println("AVISO: Não foi possível eliminar ficheiro antigo: " + oldFile.getName());
                }
            }
        }

        // Atualiza o marcador para a próxima limpeza
        oldestCleanedDay = threshold;
    }

    /**
     * Calcula métricas estatísticas agregando dados de múltiplos dias.
     * @param type Tipo de operação estatística.
     * @param prod Produto a analisar.
     * @param d Quantos dias retroativos incluir.
     * @return O valor final do cálculo.
     * @throws IOException Se for necessário ler dados do disco.
     */
    public double aggregate(int type, String prod, int d) throws IOException {
        lock.lock();
        try {
            int totalCount = 0;
            double totalVol = 0;
            double globalMax = 0;

            // Processa min(d, D, currentDay) dias para tratar todos os casos limite
            int daysToProcess = Math.min(d, Math.min(D, currentDay));

            for (int i = 1; i <= daysToProcess; i++) {
                int target = currentDay - i;
                // Verificação adicional de segurança (redundante mas segura)
                if (target < 0) continue;

                Stats s = getOrComputeStats(target, prod);
                totalCount += s.count;
                totalVol += s.vol;
                globalMax = Math.max(globalMax, s.max);
            }

            return switch (type) {
                case Protocol.AGGR_QTY -> totalCount;
                case Protocol.AGGR_VOL -> totalVol;
                case Protocol.AGGR_AVG -> totalCount == 0
                        ? 0
                        : totalVol / totalCount;
                case Protocol.AGGR_MAX -> globalMax;
                default -> 0;
            };
        } finally {
            lock.unlock();
        }
    }

    /**
     * Recupera estatísticas da cache ou processa a série se for a primeira vez.
     * @param day Dia.
     * @param prod Produto.
     * @return Estatísticas do par dia/produto.
     * @throws IOException Erro ao aceder aos dados.
     */
    private Stats getOrComputeStats(int day, String prod) throws IOException {
        aggCache.putIfAbsent(day, new HashMap<>());
        Map<String, Stats> dayCache = aggCache.get(day);

        if (dayCache.containsKey(prod)) return dayCache.get(prod);

        Stats res = new Stats();
        List<Sale> events = fetchDayEvents(day);
        for (Sale s : events) {
            if (s.prod.equals(prod)) {
                res.count += s.qty;
                res.vol += (s.qty * s.price);
                res.max = Math.max(res.max, s.price);
            }
        }
        dayCache.put(prod, res);
        return res;
    }

    /**
     * Gere o carregamento de dados do disco respeitando a cache LRU (S).
     * @param day Dia alvo.
     * @return Lista de vendas do ficheiro.
     * @throws IOException Erro de acesso ao ficheiro.
     */
    private List<Sale> fetchDayEvents(int day) throws IOException {
        if (loadedSeries.containsKey(day)) return loadedSeries.get(day);

        File f = new File("data/day_" + day + ".dat");
        if (!f.exists()) return new ArrayList<>();

        List<Sale> list = loadFile(f);

        loadedSeries.put(day, list);
        return list;
    }

    /**
     * Lê fisicamente o ficheiro dat do disco.
     * @param f Ficheiro.
     * @return Lista de vendas.
     * @throws IOException Erro de leitura.
     */
    private List<Sale> loadFile(File f) throws IOException {
        List<Sale> l = new ArrayList<>();
        try (
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(new FileInputStream(f))
                )
        ) {
            while (in.available() > 0) {
                l.add(new Sale(in.readUTF(), in.readInt(), in.readDouble()));
            }
        }
        return l;
    }

    /**
     * Filtra vendas de um dia específico.
     *
     * A validação completa é feita no ClientHandler, incluindo:
     * - day >= 0
     * - day < currentDay
     * - day >= currentDay - D (janela de retenção)
     *
     * @param day Dia.
     * @param filter Nomes permitidos.
     * @return Lista filtrada.
     * @throws IOException Erro de rede ou disco.
     */
    public List<Sale> getEventsForDay(int day, Set<String> filter)
            throws IOException {
        lock.lock();
        try {
            // Verificação de segurança (redundante se ClientHandler validou corretamente)
            if (day < 0 || day >= currentDay) {
                throw new IllegalArgumentException(
                        "Dia inválido: " + day + ". Intervalo válido: [0, " + (currentDay - 1) + "]"
                );
            }

            List<Sale> all = fetchDayEvents(day);
            List<Sale> filtered = new ArrayList<>();
            for (Sale s : all) if (filter.contains(s.prod)) filtered.add(s);
            return filtered;
        } finally {
            lock.unlock();
        }
    }

    /** Objeto de transporte para dados de venda. Campos públicos finais para imutabilidade. */
    public static class Sale {

        public final String prod;
        public final int qty;
        public final double price;

        public Sale(String p, int q, double pr) {
            prod = p;
            qty = q;
            price = pr;
        }
    }

    /** Contentor interno para caching de agregações parciais. */
    static class Stats {

        int count = 0;
        double vol = 0;
        double max = 0;
    }
}
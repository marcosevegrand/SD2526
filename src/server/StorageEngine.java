package server;

import common.Protocol;
import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Motor de armazenamento para séries temporais de vendas.
 *
 * Implementa uma hierarquia de armazenamento com três níveis:
 *   - Buffer em memória para eventos do dia atual (máxima velocidade de escrita)
 *   - Cache LRU para séries de dias anteriores (limite de S séries)
 *   - Ficheiros em disco para persistência permanente
 *
 * Adicionalmente, mantém uma cache de agregações calculadas para evitar
 * recálculos desnecessários.
 *
 * NOTA: A implementação atual usa uma cache LRU que mantém no máximo S séries
 * em memória. Para datasets extremamente grandes (biliões de eventos por dia),
 * seria necessário implementar processamento em streaming onde os eventos são
 * lidos, processados e descartados incrementalmente sem carregar todo o ficheiro
 * em memória. A implementação atual carrega ficheiros inteiros para memória,
 * sendo adequada para a maioria dos casos de uso práticos.
 */
public class StorageEngine {

    private final int S;
    private final int D;
    private final ReentrantLock lock = new ReentrantLock();
    private int currentDay = 0;
    private final List<Sale> currentEvents = new ArrayList<>();
    private final String statePath = "data/state.bin";

    /** Dia mais antigo já limpo, para otimizar a remoção de ficheiros. */
    private int oldestCleanedDay = 0;

    /**
     * Cache LRU que mantém as S séries mais recentemente acedidas em memória.
     * Utiliza LinkedHashMap com ordem de acesso ativada. Quando o limite S é
     * excedido, a entrada menos recentemente utilizada é automaticamente
     * removida através da sobrescrita de removeEldestEntry.
     */
    private final Map<Integer, List<Sale>> loadedSeries = new LinkedHashMap<>(
            16,
            0.75f,
            true
    ) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, List<Sale>> eldest) {
            return size() > S;
        }
    };

    /** Cache de agregações calculadas, indexadas por dia e produto. */
    private final Map<Integer, Map<String, Stats>> aggCache = new HashMap<>();

    /**
     * Inicializa o motor de armazenamento.
     *
     * @param S Número máximo de séries em memória
     * @param D Janela de retenção em dias
     */
    public StorageEngine(int S, int D) {
        this.S = S;
        this.D = D;
        loadState();
    }

    /**
     * Carrega o estado persistido (dia atual e marcador de limpeza).
     */
    private void loadState() {
        File f = new File(statePath);
        if (!f.exists()) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            this.currentDay = in.readInt();
            if (in.available() >= 4) {
                this.oldestCleanedDay = in.readInt();
            } else {
                this.oldestCleanedDay = Math.max(0, currentDay - D);
            }
        } catch (IOException e) {
            System.err.println("ERRO: Falha ao carregar estado do motor: " + e.getMessage());
        }
    }

    /**
     * Persiste o estado atual em disco.
     */
    private void saveState() {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(statePath))) {
            out.writeInt(currentDay);
            out.writeInt(oldestCleanedDay);
        } catch (IOException e) {
            System.err.println("ERRO: Falha ao salvar estado do motor: " + e.getMessage());
        }
    }

    /**
     * Retorna o dia atual de operação.
     *
     * @return Número do dia corrente
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
     * Adiciona um evento de venda ao buffer do dia atual.
     *
     * @param p  Identificador do produto
     * @param q  Quantidade vendida
     * @param pr Preço unitário
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
     * Persiste os eventos do dia atual e avança para o próximo dia.
     * Os eventos são escritos para um ficheiro numerado, as caches são
     * atualizadas e ficheiros fora da janela de retenção são removidos.
     *
     * @throws IOException Se ocorrer erro de escrita
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

            // Limpeza de dados fora da janela de retenção
            int threshold = currentDay - D;
            aggCache.keySet().removeIf(day -> day < threshold);
            loadedSeries.keySet().removeIf(day -> day < threshold);
            cleanupOldFiles(threshold);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove ficheiros de dias fora da janela de retenção.
     *
     * @param threshold Dias abaixo deste valor são removidos
     */
    private void cleanupOldFiles(int threshold) {
        if (threshold <= oldestCleanedDay) {
            return;
        }

        for (int day = oldestCleanedDay; day < threshold; day++) {
            File oldFile = new File("data/day_" + day + ".dat");
            if (oldFile.exists()) {
                if (!oldFile.delete()) {
                    System.err.println("AVISO: Não foi possível eliminar ficheiro antigo: " + oldFile.getName());
                }
            }
        }

        oldestCleanedDay = threshold;
    }

    /**
     * Calcula uma agregação estatística sobre dados históricos.
     *
     * @param type Tipo de agregação (QTY, VOL, AVG, MAX)
     * @param prod Produto a analisar
     * @param d    Número de dias retroativos
     * @return Valor calculado da agregação
     * @throws IOException Se ocorrer erro ao ler dados do disco
     */
    public double aggregate(int type, String prod, int d) throws IOException {
        lock.lock();
        try {
            int totalCount = 0;
            double totalVol = 0;
            double globalMax = 0;

            int daysToProcess = Math.min(d, Math.min(D, currentDay));

            for (int i = 1; i <= daysToProcess; i++) {
                int target = currentDay - i;
                if (target < 0) {
                    continue;
                }

                Stats s = getOrComputeStats(target, prod);
                totalCount += s.count;
                totalVol += s.vol;
                globalMax = Math.max(globalMax, s.max);
            }

            return switch (type) {
                case Protocol.AGGR_QTY -> totalCount;
                case Protocol.AGGR_VOL -> totalVol;
                case Protocol.AGGR_AVG -> totalCount == 0 ? 0 : totalVol / totalCount;
                case Protocol.AGGR_MAX -> globalMax;
                default -> 0;
            };
        } finally {
            lock.unlock();
        }
    }

    /**
     * Obtém estatísticas da cache ou calcula-as a partir dos dados.
     *
     * @param day  Dia a processar
     * @param prod Produto a analisar
     * @return Estatísticas calculadas
     * @throws IOException Se ocorrer erro de leitura
     */
    private Stats getOrComputeStats(int day, String prod) throws IOException {
        aggCache.putIfAbsent(day, new HashMap<>());
        Map<String, Stats> dayCache = aggCache.get(day);

        if (dayCache.containsKey(prod)) {
            return dayCache.get(prod);
        }

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
     * Carrega eventos de um dia, utilizando a cache LRU se disponível.
     *
     * @param day Dia a carregar
     * @return Lista de eventos do dia
     * @throws IOException Se ocorrer erro de leitura
     */
    private List<Sale> fetchDayEvents(int day) throws IOException {
        if (loadedSeries.containsKey(day)) {
            return loadedSeries.get(day);
        }

        File f = new File("data/day_" + day + ".dat");
        if (!f.exists()) {
            return new ArrayList<>();
        }

        List<Sale> list = loadFile(f);
        loadedSeries.put(day, list);
        return list;
    }

    /**
     * Lê um ficheiro de dados do disco.
     *
     * @param f Ficheiro a ler
     * @return Lista de eventos
     * @throws IOException Se ocorrer erro de leitura
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
     * Filtra eventos de um dia específico por produto.
     *
     * @param day    Dia a consultar (deve ser menor que o dia atual)
     * @param filter Conjunto de produtos a incluir
     * @return Lista de eventos filtrados
     * @throws IOException Se ocorrer erro de leitura
     */
    public List<Sale> getEventsForDay(int day, Set<String> filter)
            throws IOException {
        lock.lock();
        try {
            if (day < 0 || day >= currentDay) {
                throw new IllegalArgumentException(
                        "Dia inválido: " + day + ". Intervalo válido: [0, " + (currentDay - 1) + "]"
                );
            }

            List<Sale> all = fetchDayEvents(day);
            List<Sale> filtered = new ArrayList<>();
            for (Sale s : all) {
                if (filter.contains(s.prod)) {
                    filtered.add(s);
                }
            }
            return filtered;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Representação imutável de um evento de venda.
     */
    public static class Sale {

        /** Identificador do produto. */
        public final String prod;

        /** Quantidade vendida. */
        public final int qty;

        /** Preço unitário. */
        public final double price;

        /**
         * Cria um novo evento de venda.
         *
         * @param p  Produto
         * @param q  Quantidade
         * @param pr Preço
         */
        public Sale(String p, int q, double pr) {
            prod = p;
            qty = q;
            price = pr;
        }
    }

    /**
     * Contentor para estatísticas agregadas de um produto num dia.
     */
    static class Stats {
        int count = 0;
        double vol = 0;
        double max = 0;
    }
}
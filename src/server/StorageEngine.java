package server;

import common.Protocol;
import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Motor de armazenamento especializado em séries temporais com gestão de memória.
 * A intenção é suportar grandes volumes de dados através de persistência em ficheiro,
 * cache LRU para séries frequentes e cache preguiçosa (lazy) para resultados agregados.
 * O motor preserva o contador do dia atual entre reinicializações.
 */
public class StorageEngine {

    private final int S;
    private final int D;
    private final ReentrantLock lock = new ReentrantLock();
    private int currentDay = 0;
    private final List<Sale> currentEvents = new ArrayList<>();
    private final String statePath = "data/state.bin";

    /**
     * Cache LRU (Least Recently Used) para manter apenas as S séries mais usadas em memória.
     * Quando o limite S é atingido, a série mais antiga é removida do mapa para dar lugar a uma nova.
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
     * CORRIGIDO: Rastreia séries que estão a ser processadas atualmente na agregação.
     * Impede que séries ativas sejam removidas do cache LRU antes do fim da agregação.
     */
    private final Set<Integer> activelyProcessing = new HashSet<>();

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
     * A intenção é evitar a sobreposição de ficheiros de dados após um restart.
     */
    private void loadState() {
        File f = new File(statePath);
        if (!f.exists()) return;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            this.currentDay = in.readInt();
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
     * Adiciona um evento ao buffer do dia atual (que está sempre em RAM).
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
     * Escreve os eventos acumulados do dia em disco, limpa dados obsoletos, e atualiza o estado persistente.
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

            // Remoção proativa de dados que saírram da janela de interesse (D)
            int threshold = currentDay - D;
            aggCache.keySet().removeIf(day -> day < threshold);
            loadedSeries.keySet().removeIf(day -> day < threshold);
        } finally {
            lock.unlock();
        }
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
            int daysToProcess = Math.min(d, D);

            for (int i = 1; i <= daysToProcess; i++) {
                int target = currentDay - i;
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
     * CORRIGIDO: Marca o dia como sendo processado para evitar que seja removido da cache LRU.
     * @param day Dia.
     * @param prod Produto.
     * @return Estatísticas do par dia/produto.
     * @throws IOException Erro ao aceder aos dados.
     */
    private Stats getOrComputeStats(int day, String prod) throws IOException {
        aggCache.putIfAbsent(day, new HashMap<>());
        Map<String, Stats> dayCache = aggCache.get(day);

        if (dayCache.containsKey(prod)) return dayCache.get(prod);

        // Se não houver cache, temos de percorrer toda a série do dia
        activelyProcessing.add(day);  // Marca como em processamento
        try {
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
        } finally {
            activelyProcessing.remove(day);  // Remove marcação
        }
    }

    /**
     * Gere o carregamento de dados do disco respeitando a cache LRU (S).
     * CORRIGIDO: Não adiciona à cache se já existem S séries e a série não está sendo processada.
     * @param day Dia alvo.
     * @return Lista de vendas do ficheiro.
     * @throws IOException Erro de acesso ao ficheiro.
     */
    private List<Sale> fetchDayEvents(int day) throws IOException {
        if (loadedSeries.containsKey(day)) return loadedSeries.get(day);

        File f = new File("data/day_" + day + ".dat");
        if (!f.exists()) return new ArrayList<>();

        List<Sale> list = loadFile(f);
        
        // CORRIGIDO: Apenas adiciona à cache se há espaço ou se está sendo processada
        if (loadedSeries.size() < S || activelyProcessing.contains(day)) {
            loadedSeries.put(day, list);
        }
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
     * @param day Dia.
     * @param filter Nomes permitidos.
     * @return Lista filtrada.
     * @throws IOException Erro de rede ou disco.
     */
    public List<Sale> getEventsForDay(int day, Set<String> filter)
        throws IOException {
        lock.lock();
        try {
            List<Sale> all = (day == currentDay)
                ? new ArrayList<>(currentEvents)
                : fetchDayEvents(day);
            List<Sale> filtered = new ArrayList<>();
            for (Sale s : all) if (filter.contains(s.prod)) filtered.add(s);
            return filtered;
        } finally {
            lock.unlock();
        }
    }

    /** Objeto de transporte para dados de venda. */
    public static class Sale {

        String prod;
        int qty;
        double price;

        Sale(String p, int q, double pr) {
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

package server;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordenador de notificações em tempo real.
 *
 * Permite que threads de clientes aguardem passivamente por eventos de negócio
 * específicos: vendas simultâneas de dois produtos ou sequências de vendas
 * consecutivas do mesmo produto. As threads são acordadas quando as condições
 * são satisfeitas ou quando o dia termina.
 */
public class NotificationManager {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition change = lock.newCondition();
    private final Set<String> soldToday = new HashSet<>();
    private String lastProductSold = null;
    private int consecutiveCount = 0;
    private int currentDay = 0;

    /**
     * Histórico de produtos que atingiram cada contagem consecutiva durante o dia.
     * Permite que threads que iniciem a espera após a condição já ter sido
     * satisfeita sejam imediatamente notificadas.
     */
    private final Map<Integer, Set<String>> streaksReached = new HashMap<>();

    /**
     * Regista uma venda e verifica se as metas de notificação foram atingidas.
     * Atualiza o conjunto de produtos vendidos no dia e a contagem de vendas
     * consecutivas. Notifica todas as threads em espera para que verifiquem
     * as suas condições.
     *
     * @param product Nome do produto vendido
     */
    public void registerSale(String product) {
        lock.lock();
        try {
            soldToday.add(product);

            // Atualização da contagem de vendas consecutivas
            if (product.equals(lastProductSold)) {
                consecutiveCount++;
            } else {
                lastProductSold = product;
                consecutiveCount = 1;
            }

            // Regista que esta contagem foi atingida por este produto
            streaksReached.putIfAbsent(consecutiveCount, new HashSet<>());
            streaksReached.get(consecutiveCount).add(product);

            change.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reinicia o estado diário e notifica o fim do ciclo às threads em espera.
     */
    public void newDay() {
        lock.lock();
        try {
            currentDay++;
            soldToday.clear();
            streaksReached.clear();
            lastProductSold = null;
            consecutiveCount = 0;
            change.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Aguarda até que ambos os produtos sejam vendidos no mesmo dia.
     *
     * @param p1 Primeiro produto
     * @param p2 Segundo produto
     * @return true se a condição foi cumprida, false se o dia terminou
     * @throws InterruptedException Se a thread for interrompida
     */
    public boolean waitSimultaneous(String p1, String p2)
            throws InterruptedException {
        lock.lock();
        try {
            int startDay = currentDay;
            while (
                    currentDay == startDay &&
                            (!soldToday.contains(p1) || !soldToday.contains(p2))
            ) {
                change.await();
            }
            return currentDay == startDay;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Aguarda por uma sequência de N vendas consecutivas de qualquer produto.
     * Verifica primeiro se a condição já foi satisfeita antes de bloquear,
     * utilizando o histórico de streaks atingidos durante o dia.
     *
     * @param n Número de vendas consecutivas pretendido
     * @return Nome do produto que atingiu a meta, ou null se o dia terminou
     * @throws InterruptedException Se a thread for interrompida
     */
    public String waitConsecutive(int n) throws InterruptedException {
        lock.lock();
        try {
            int startDay = currentDay;
            while (currentDay == startDay && !streaksReached.containsKey(n)) {
                change.await();
            }
            if (currentDay == startDay) {
                Set<String> products = streaksReached.get(n);
                if (products != null && !products.isEmpty()) {
                    return products.iterator().next();
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }
}
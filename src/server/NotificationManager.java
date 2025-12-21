package server;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordenador central de eventos e bloqueios em tempo real.
 * A intenção é permitir que threads de utilizadores fiquem em espera passiva por eventos
 * de negócio, sendo acordadas apenas quando as condições de venda são satisfeitas ou
 * quando o dia termina.
 */
public class NotificationManager {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition change = lock.newCondition();
    private final Set<String> soldToday = new HashSet<>();
    private String lastProductSold = null;
    private int consecutiveCount = 0;
    private int currentDay = 0;
    private final Map<Integer, String> streaksReached = new HashMap<>();

    /**
     * Regista uma venda e verifica se as metas de notificação foram atingidas.
     * O uso do mapa 'streaksReached' é uma salvaguarda para que metas atingidas
     * entre o despertar e a aquisição do lock não sejam perdidas.
     * @param product Nome do produto vendido.
     */
    public void registerSale(String product) {
        lock.lock();
        try {
            soldToday.add(product);

            // Verifica continuidade para vendas consecutivas
            if (product.equals(lastProductSold)) {
                consecutiveCount++;
            } else {
                lastProductSold = product;
                consecutiveCount = 1;
            }

            streaksReached.put(consecutiveCount, product);
            // Acorda todas as threads que podem estar à espera deste produto ou quantidade
            change.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reinicia o estado diário e sinaliza o fim do ciclo às threads bloqueadas.
     * Ao incrementar 'currentDay', permitimos que as threads em espera percebam que
     * a sua janela temporal expirou.
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
     * Coloca o pedido em espera até que ambos os produtos tenham sido vendidos.
     * @param p1 Primeiro produto alvo.
     * @param p2 Segundo produto alvo.
     * @return true se a condição foi cumprida, false se o dia fechou sem sucesso.
     * @throws InterruptedException Se a thread for interrompida.
     */
    public boolean waitSimultaneous(String p1, String p2)
        throws InterruptedException {
        lock.lock();
        try {
            int startDay = currentDay;
            // Espera enquanto o dia for o mesmo e a condição de ambos vendidos não for real
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
     * Aguarda por uma sequência de N vendas seguidas de qualquer produto.
     * @param n Quantidade consecutiva pretendida.
     * @return Nome do produto que atingiu a marca, ou null se o dia terminou.
     * @throws InterruptedException Se houver interrupção da thread.
     */
    public String waitConsecutive(int n) throws InterruptedException {
        lock.lock();
        try {
            int startDay = currentDay;
            // Verifica o histórico de metas atingidas no dia atual antes de bloquear
            while (currentDay == startDay && !streaksReached.containsKey(n)) {
                change.await();
            }
            return (currentDay == startDay) ? streaksReached.get(n) : null;
        } finally {
            lock.unlock();
        }
    }
}

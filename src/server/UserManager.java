package server;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Responsável pelo ciclo de vida das credenciais de utilizador.
 * A intenção é fornecer uma base segura e atómica para operações de registo e login,
 * garantindo que não existem colisões de nomes de utilizador em acessos concorrentes.
 */
public class UserManager {

    private final Map<String, String> users = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Tenta persistir um novo conjunto de credenciais no mapa de utilizadores.
     * @param u Nome do utilizador único.
     * @param p Palavra-passe em texto limpo.
     * @return true se o utilizador foi criado, false se o nome já estiver reservado.
     */
    public boolean register(String u, String p) {
        lock.lock();
        try {
            if (users.containsKey(u)) return false;
            users.put(u, p);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifica se as credenciais fornecidas coincidem com os registos guardados.
     * @param u Nome de utilizador.
     * @param p Palavra-passe a validar.
     * @return true se os dados estiverem corretos, false caso contrário.
     */
    public boolean authenticate(String u, String p) {
        lock.lock();
        try {
            String stored = users.get(u);
            return stored != null && stored.equals(p);
        } finally {
            lock.unlock();
        }
    }
}

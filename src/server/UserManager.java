package server;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gestor de credenciais de utilizadores.
 *
 * Responsável pelo ciclo de vida das contas: registo, autenticação e persistência.
 * Utiliza um lock para garantir atomicidade nas operações de escrita concorrente
 * e persiste os dados em disco para sobreviver a reinicializações do servidor.
 */
public class UserManager {

    private final Map<String, String> users = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final String filePath = "data/users.bin";

    /**
     * Inicializa o gestor e carrega utilizadores persistidos anteriormente.
     */
    public UserManager() {
        loadUsers();
    }

    /**
     * Carrega a base de dados de utilizadores do disco.
     */
    private void loadUsers() {
        File f = new File(filePath);
        if (!f.exists()) {
            return;
        }
        try (
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(new FileInputStream(f))
                )
        ) {
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                users.put(in.readUTF(), in.readUTF());
            }
        } catch (IOException e) {
            System.err.println("ERRO: Falha ao carregar utilizadores: " + e.getMessage());
        }
    }

    /**
     * Persiste o mapa de utilizadores em disco.
     */
    private void saveUsers() {
        try (
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(new FileOutputStream(filePath))
                )
        ) {
            out.writeInt(users.size());
            for (Map.Entry<String, String> entry : users.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
        } catch (IOException e) {
            System.err.println("ERRO: Falha ao persistir utilizadores: " + e.getMessage());
        }
    }

    /**
     * Regista um novo utilizador no sistema.
     * A operação é atómica: o utilizador é adicionado ao mapa e os dados
     * são imediatamente persistidos em disco.
     *
     * @param u Nome de utilizador
     * @param p Palavra-passe
     * @return true se o registo foi bem-sucedido, false se o utilizador já existe
     */
    public boolean register(String u, String p) {
        lock.lock();
        try {
            if (users.containsKey(u)) {
                return false;
            }
            users.put(u, p);
            saveUsers();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifica as credenciais de um utilizador.
     *
     * @param u Nome de utilizador
     * @param p Palavra-passe a validar
     * @return true se as credenciais são válidas
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
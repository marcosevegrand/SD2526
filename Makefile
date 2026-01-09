# ==============================================================================
# Grupo 9 - Sistemas Distribuídos
# ==============================================================================

# Parâmetros configuráveis com valores por omissão
PORT ?= 12345
S ?= 10
D ?= 365
THREADS ?= 100
HOST ?= localhost
CLIENT_PORT ?= 12345

.PHONY: all run-server run-cli run-feat-test run-stress-test report clean

# ==============================================================================
# Compilação
# ==============================================================================

# Compila todo o projeto
all:
	mkdir -p bin
	mkdir -p data
	javac -d bin src/common/*.java src/client/*.java src/server/*.java
	javac -d bin -cp bin test/*.java
	@echo "Build complete!"
	@echo "To run the server: make run-server [PORT=port] [S=series] [D=days]"
	@echo "To run the client: make run-cli [HOST=host] [PORT=port]"

# ==============================================================================
# Execução
# ==============================================================================

# Executa o servidor com parâmetros configuráveis
# Exemplos:
#   make run-server                                    # Usa valores por omissão
#   make run-server PORT=8080 S=50 D=730 THREADS=200  # Personalizado
run-server:
	@echo "Starting server: PORT=$(PORT), S=$(S), D=$(D), THREADS=$(THREADS)"
	java -cp bin server.Server $(PORT) $(S) $(D) $(THREADS)

# Executa o cliente com parâmetros configuráveis
# Exemplos:
#   make run-cli                             # Usa valores por omissão
#   make run-cli HOST=example.com            # Host personalizado
#   make run-cli HOST=example.com PORT=8080  # Host e porto personalizados
run-cli:
	@echo "Connecting to server at: $(HOST):$(CLIENT_PORT)"
	java -cp bin client.UI $(HOST) $(CLIENT_PORT)

# ==============================================================================
# Testes
# ==============================================================================

# Executa testes de carga
run-stress-test:
	@echo "Running stress tests..."
	java -cp bin test.StressTest

# Executa testes de robustez
run-robust-test:
	@echo "Running robust tests..."
	java -cp bin test.RobustTest

# Executa testes de cache (recomenda-se S=3 D=10)
run-cache-test:
	@echo "Running cache tests (use S=3 D=10)..."
	java -cp bin test.CacheTest

# ==============================================================================
# Documentação
# ==============================================================================

# Gera documentação Javadoc
docs:
	mkdir -p docs
	javadoc -d docs -cp bin src/common/*.java src/client/*.java src/server/*.java
	@echo "Documentation generated in docs/"

# Compila o relatório PDF
report:
	@echo "Building report PDF..."
	cd report/latex && pdflatex -interaction=nonstopmode -halt-on-error report.tex
	cd report/latex && pdflatex -interaction=nonstopmode -halt-on-error report.tex
	mv -f report/latex/report.pdf report/report.pdf
	rm -f report/latex/*.aux report/latex/*.log report/latex/*.out report/latex/*.toc report/latex/*.synctex.gz report/latex/report.pdf
	@echo "Report generated at report/report.pdf"

# ==============================================================================
# Limpeza
# ==============================================================================

# Remove artefactos de compilação
clean:
	rm -rf bin data docs
	@echo "Clean complete"
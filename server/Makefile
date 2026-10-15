CXX ?= g++
CXXFLAGS ?= -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread

PG_CONFIG ?= pg_config
LDLIBS += -lpq

.PHONY: all test test-db clean
all: myct-core

myct-core: main.cpp postgres.cpp postgres.hpp
	@command -v $(PG_CONFIG) >/dev/null || { echo "Missing pg_config: install libpq-devel (requires authorization)" >&2; exit 1; }
	$(CXX) $(CPPFLAGS) -I"$$($(PG_CONFIG) --includedir)" $(CXXFLAGS) main.cpp postgres.cpp $(LDFLAGS) -L"$$($(PG_CONFIG) --libdir)" $(LDLIBS) -o $@

test: myct-core
	python3 -m unittest discover -s tests -v

test-db: myct-core
	python3 tests/integration_postgres.py

clean:
	rm -f myct-core

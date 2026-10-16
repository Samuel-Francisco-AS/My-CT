CXX ?= g++
CXXFLAGS ?= -std=c++20 -O2 -Wall -Wextra -Wpedantic -pthread

PG_CONFIG ?= pg_config
LDLIBS += -lpq

.PHONY: all test test-db clean
all: myct-core myct-download myct-inspect myct-av-scan

myct-core: main.cpp postgres.cpp postgres.hpp
	@command -v $(PG_CONFIG) >/dev/null || { echo "Missing pg_config: install libpq-devel (requires authorization)" >&2; exit 1; }
	$(CXX) $(CPPFLAGS) -I"$$($(PG_CONFIG) --includedir)" $(CXXFLAGS) main.cpp postgres.cpp $(LDFLAGS) -L"$$($(PG_CONFIG) --libdir)" $(LDLIBS) -o $@

myct-download: download.cpp
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $< $(LDFLAGS) -o $@

myct-inspect: inspect.cpp safe_file.hpp
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $< $(LDFLAGS) -lcrypto -o $@

myct-av-scan: av_scan.cpp safe_file.hpp
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $< $(LDFLAGS) -o $@

test: myct-core myct-download myct-inspect myct-av-scan
	python3 -m unittest discover -s tests -v

test-db: myct-core
	python3 tests/integration_postgres.py

clean:
	rm -f myct-core myct-download myct-inspect myct-av-scan

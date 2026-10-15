#include "postgres.hpp"

#include <libpq-fe.h>
#include <pwd.h>
#include <unistd.h>

#include <charconv>
#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <string>

namespace {
using Connection = std::unique_ptr<PGconn, decltype(&PQfinish)>;
using Result = std::unique_ptr<PGresult, decltype(&PQclear)>;

Connection connect_local() {
    const char* socket = std::getenv("MYCT_PGSOCKET");
    if (!socket || socket[0] != '/' || std::string(socket).find(',') != std::string::npos) {
        throw std::runtime_error("set MYCT_PGSOCKET to the absolute Unix socket directory");
    }
    const passwd* account = getpwuid(geteuid());
    if (!account) throw std::runtime_error("cannot identify local database user");
    // libpq ignores empty parameter values, so hostaddr="" would still allow
    // PGHOSTADDR to override a Unix socket. Clear address/service overrides in
    // this short-lived CLI process before calling libpq (never done in HTTP).
    if (unsetenv("PGHOSTADDR") != 0 || unsetenv("PGSERVICE") != 0) {
        throw std::runtime_error("cannot isolate local PostgreSQL connection");
    }
    const char* keys[] = {"host", "port", "dbname", "user",
                          "connect_timeout", "options", "application_name", nullptr};
    const char* values[] = {socket, "5432", "myct", account->pw_name,
                            "3", "-c search_path=pg_catalog -c statement_timeout=3000 -c lock_timeout=3000",
                            "myct-core", nullptr};
    Connection connection(PQconnectdbParams(keys, values, 0), PQfinish);
    if (!connection || PQstatus(connection.get()) != CONNECTION_OK) {
        // Do not print libpq's raw diagnostics (may include connection details).
        throw std::runtime_error("PostgreSQL unavailable; check the private cluster, socket and peer authentication");
    }
    return connection;
}

std::string query_event(const char* sql, const std::string& parameter) {
    auto connection = connect_local();
    const char* values[] = {parameter.c_str()};
    Result result(PQexecParams(connection.get(), sql, 1, nullptr, values,
                               nullptr, nullptr, 0), PQclear);
    if (!result || PQresultStatus(result.get()) != PGRES_TUPLES_OK) {
        const char* state = result ? PQresultErrorField(result.get(), PG_DIAG_SQLSTATE) : nullptr;
        throw std::runtime_error(std::string("PostgreSQL query failed; check migration/permissions (SQLSTATE ")
                                 + (state ? state : "unknown") + ")");
    }
    if (PQntuples(result.get()) != 1) throw std::runtime_error("event not found");
    return std::string(PQgetvalue(result.get(), 0, 0)) + "\t"
        + PQgetvalue(result.get(), 0, 1) + "\t" + PQgetvalue(result.get(), 0, 2);
}
} // namespace

std::string record_event(const std::string& event_type) {
    if (event_type.empty() || event_type.size() > 64 ||
        event_type.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-")
            != std::string::npos) {
        throw std::runtime_error("event type must contain 1..64 ASCII letters, digits, _, . or -");
    }
    return query_event("INSERT INTO public.execution_events (event_type) VALUES ($1) "
                       "RETURNING id, to_char(occurred_at AT TIME ZONE 'UTC', "
                       "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'), event_type", event_type);
}

std::string get_event(const std::string& id) {
    long long value = 0;
    const auto parsed = std::from_chars(id.data(), id.data() + id.size(), value);
    if (parsed.ec != std::errc{} || parsed.ptr != id.data() + id.size() || value <= 0) {
        throw std::runtime_error("event id must be a positive bigint");
    }
    return query_event("SELECT id, to_char(occurred_at AT TIME ZONE 'UTC', "
                       "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'), event_type "
                       "FROM public.execution_events WHERE id = $1::bigint", id);
}

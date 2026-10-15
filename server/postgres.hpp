#pragma once

#include <string>

// Explicit, short-lived CLI operations; health never calls these functions.
std::string record_event(const std::string& event_type);
std::string get_event(const std::string& id);

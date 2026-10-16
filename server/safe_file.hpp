#pragma once
#include <linux/openat2.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <pwd.h>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>
struct Fd { int n; ~Fd() { if(n >= 0) close(n); } };
static std::string json(const std::string& s) {
    std::ostringstream o; o << '"';
    for(unsigned char c : s) {
        if(c == '"' || c == '\\') o << '\\' << c;
        else if(c < 32) o << "\\u" << std::hex << std::setw(4) << std::setfill('0') << int(c);
        else o << c;
    }
    return o.str() + '"';
}
static void check(bool ok, const char* message) { if(!ok) throw std::runtime_error(message); }
static std::string decode(const std::string& s) {
    check(!s.empty() && s.size() <= 32768 && s.size()%2 == 0, "Caminho inválido.");
    std::string out;
    for(size_t i=0;i<s.size();i+=2) {
        auto digit=[](char c) { return c>='0' && c<='9' ? c-'0' : c>='a' && c<='f' ? c-'a'+10 : -1; };
        int a=digit(s[i]), b=digit(s[i+1]);
        check(a>=0 && b>=0, "Caminho inválido.");
        unsigned char c=a*16+b;
        check(c>=32 && c!=127, "Caminho contém controles não suportados."); out+=c;
    }
    return out;
}

// Same authorization as MYCT-5D-A; AV also requires O_NOATIME (fail closed).
static int openHomeFile(const std::string& path, bool noAtime=false) {
    auto user=getpwuid(getuid()); check(user,"Usuário Linux indisponível.");
    std::string home=user->pw_dir, prefix=home+"/";
    check(path.starts_with(prefix),"Caminho fora do home autorizado do usuário SSH.");
    auto relative=path.substr(prefix.size());
    std::istringstream components(relative); std::string part;
    while(std::getline(components,part,'/'))
        check(!part.empty() && part!="." && part!="..","Caminho não autorizado.");
    Fd root{open(home.c_str(),O_PATH|O_DIRECTORY|O_CLOEXEC)};
    check(root.n>=0,"Acesso ao home negado.");
    open_how how{};
    how.flags=O_RDONLY|O_NONBLOCK|O_CLOEXEC|(noAtime ? O_NOATIME : 0);
    how.resolve=RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS;
    int fd=int(syscall(SYS_openat2,root.n,relative.c_str(),&how,sizeof how));
    if(fd<0) {
        if(errno==ENOENT) throw std::runtime_error("Arquivo inexistente.");
        if(errno==EACCES || errno==EPERM) throw std::runtime_error("Sem permissão de leitura/O_NOATIME.");
        throw std::runtime_error("Caminho recusado: symlink, escape ou acesso indisponível.");
    }
    struct stat st{};
    if(fstat(fd,&st)!=0 || !S_ISREG(st.st_mode)) {
        close(fd); throw std::runtime_error("Selecione um arquivo regular.");
    }
    return fd;
}

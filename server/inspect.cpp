// One read-only inspection. No daemon, shell, database or persistent state.
#include <openssl/evp.h>
#include <linux/openat2.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <poll.h>
#include <pwd.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <cerrno>
#include <chrono>
#include <iostream>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>
#include <array>

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
static void connected(bool watched) {
    if(!watched) return;
    pollfd p{STDIN_FILENO, POLLIN, 0};
    if(poll(&p,1,0)>0) {
        char b[32];
        check(!(p.revents & (POLLHUP|POLLERR|POLLNVAL)) && read(0,b,sizeof b)>0,
              "Inspeção incompleta: cliente desconectado ou cancelou.");
    }
}
static std::string typeOf(int fd, bool watched) {
    int p[2]; if(pipe2(p,O_CLOEXEC)!=0) return "";
    Fd input{p[0]}, output{p[1]};
    pid_t child=fork();
    if(child<0) return "";
    if(child==0) {
        prctl(PR_SET_PDEATHSIG,SIGKILL);
        dup2(output.n,1); int nullfd=open("/dev/null",O_WRONLY); dup2(nullfd,2);
        // /proc opens the already validated inode, never reopens the supplied path.
        std::string path="/proc/self/fd/"+std::to_string(fd);
        fcntl(fd,F_SETFD,0);
        execl("/usr/bin/file","file","-b","-L","--mime-type","--",path.c_str(),nullptr);
        _exit(127);
    }
    close(output.n); output.n=-1;
    std::string result; bool done=false; int status=0;
    auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(5);
    try {
        while(!done) {
            connected(watched);
            pollfd event{input.n,POLLIN,0}; poll(&event,1,50);
            if(event.revents&POLLIN) {
                char b[256]; auto count=read(input.n,b,sizeof b);
                if(count>0) result.append(b,count);
                check(result.size()<=512,"Identificação incompleta.");
            }
            done=waitpid(child,&status,WNOHANG)==child;
            if(done) {
                char b[256]; ssize_t n;
                while((n=read(input.n,b,sizeof b))>0) {
                    result.append(b,n); check(result.size()<=512,"Identificação incompleta.");
                }
            }
            check(std::chrono::steady_clock::now()<deadline,"Identificação excedeu tempo limite.");
        }
    } catch(...) { kill(child,SIGKILL); waitpid(child,&status,0); throw; }
    if(!WIFEXITED(status) || WEXITSTATUS(status)!=0) return "";
    while(!result.empty() && (result.back()=='\n' || result.back()=='\r')) result.pop_back();
    return result;
}
int main(int argc,char** argv) {
    try {
        check(argc==2 || (argc==3 && std::string(argv[2])=="--watch-stdin"),"Uso: myct-inspect CAMINHO_UTF8_HEX [--watch-stdin]");
        bool watched=argc==3;
        auto path=decode(argv[1]);
        auto user=getpwuid(getuid()); check(user,"Usuário Linux indisponível.");
        std::string home=user->pw_dir, prefix=home+"/";
        check(path.starts_with(prefix),"Caminho fora do home autorizado do usuário SSH.");
        auto relative=path.substr(prefix.size());
        std::istringstream components(relative); std::string part;
        while(std::getline(components,part,'/')) check(!part.empty() && part!="." && part!="..","Caminho não autorizado.");
        Fd root{open(home.c_str(),O_PATH|O_DIRECTORY|O_CLOEXEC)};
        check(root.n>=0,"Acesso ao home negado.");
        open_how how{}; how.flags=O_RDONLY|O_NONBLOCK|O_CLOEXEC; how.resolve=RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS;
        Fd file{int(syscall(SYS_openat2,root.n,relative.c_str(),&how,sizeof how))};
        if(file.n<0) {
            if(errno==ENOENT) throw std::runtime_error("Arquivo inexistente.");
            if(errno==EACCES) throw std::runtime_error("Sem permissão de leitura.");
            throw std::runtime_error("Caminho recusado: symlink, escape ou acesso indisponível.");
        }
        struct stat before{},after{};
        check(fstat(file.n,&before)==0 && S_ISREG(before.st_mode),"Selecione um arquivo regular; diretórios e tipos especiais são recusados.");
        connected(watched);
        auto type=typeOf(file.n,watched);
        EVP_MD_CTX* raw=EVP_MD_CTX_new(); check(raw,"SHA-256 indisponível.");
        struct Context { EVP_MD_CTX* p; ~Context(){EVP_MD_CTX_free(p);} } ctx{raw};
        check(EVP_DigestInit_ex(ctx.p,EVP_sha256(),nullptr)==1,"SHA-256 indisponível.");
        std::array<char,65536> buffer{}; off_t bytes=0;
        auto deadline=std::chrono::steady_clock::now()+std::chrono::minutes(30);
        while(true) {
            connected(watched);
            check(std::chrono::steady_clock::now()<deadline,"Inspeção incompleta: limite de 30 minutos.");
            auto n=read(file.n,buffer.data(),buffer.size());
            check(n>=0,"Inspeção incompleta: falha de leitura."); if(n==0) break;
            bytes+=n; check(EVP_DigestUpdate(ctx.p,buffer.data(),n)==1,"Inspeção incompleta: falha SHA-256.");
        }
        check(fstat(file.n,&after)==0 && bytes==before.st_size && before.st_size==after.st_size &&
              before.st_mtim.tv_sec==after.st_mtim.tv_sec && before.st_mtim.tv_nsec==after.st_mtim.tv_nsec &&
              before.st_ctim.tv_sec==after.st_ctim.tv_sec && before.st_ctim.tv_nsec==after.st_ctim.tv_nsec,
              "Inspeção incompleta: arquivo mudou durante a leitura. Aguarde a conclusão do download.");
        unsigned char digest[EVP_MAX_MD_SIZE]; unsigned int count=0;
        check(EVP_DigestFinal_ex(ctx.p,digest,&count)==1 && count==32,"Inspeção incompleta: falha SHA-256.");
        std::ostringstream hash; for(unsigned i=0;i<count;i++) hash<<std::hex<<std::setw(2)<<std::setfill('0')<<int(digest[i]);
        auto name=path.substr(path.rfind('/')+1); auto dot=name.rfind('.');
        std::cout << "{\"version\":1,\"path\":" << json(path) << ",\"name\":" << json(name)
                  << ",\"bytes\":" << bytes << ",\"extension\":" << json(dot==std::string::npos || dot==0 ? "" : name.substr(dot+1))
                  << ",\"type\":" << json(type) << ",\"sha256\":" << json(hash.str())
                  << ",\"warning\":" << json(type.empty() || type=="application/octet-stream" ? "Formato não identificado pelo conteúdo; SHA-256 calculado. Não há análise antivírus." : "SHA-256 não comprova origem nem ausência de malware.") << "}\n";
        return std::cout ? 0 : 1;
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; }
}

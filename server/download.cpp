// MYCT-5A: local owner CLI; curl transports bytes, user systemd owns lifetime.
#include <arpa/inet.h>
#include <netdb.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <signal.h>
#include <algorithm>
#include <cctype>
#include <cerrno>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <regex>
#include <stdexcept>
#include <string>
#include <vector>
namespace fs = std::filesystem;
using namespace std::string_literals;
static volatile sig_atomic_t canceled = 0;
static void stop(int) { canceled = 1; }
static std::string line(const fs::path& p) { std::ifstream f(p); std::string s; std::getline(f,s); return s; }
static void put(const fs::path& p, const std::string& s) {
    std::ofstream f(p.string()+".tmp"); f << s << '\n'; f.close();
    if (!f) throw std::runtime_error("Não foi possível gravar metadados (disco/permissões).");
    fs::rename(p.string()+".tmp",p);
}
static int run(std::vector<std::string> args, const fs::path& out = {}, const fs::path& err = {}) {
    pid_t pid = fork();
    if (pid < 0) throw std::runtime_error("fork falhou");
    if (!pid) {
        if (!out.empty()) { int fd=open(out.c_str(),O_WRONLY|O_CREAT|O_TRUNC,0600); if(fd<0) _exit(126); dup2(fd,1); close(fd); }
        if (!err.empty()) { int fd=open(err.c_str(),O_WRONLY|O_CREAT|O_TRUNC,0600); if(fd<0) _exit(126); dup2(fd,2); close(fd); }
        std::vector<char*> argv; for(auto& s:args) argv.push_back(s.data()); argv.push_back(nullptr);
        execvp(argv[0],argv.data()); _exit(127);
    }
    if(canceled) kill(pid,SIGTERM);
    int status=0;
    while(waitpid(pid,&status,0)<0) { if(errno!=EINTR) throw std::runtime_error("waitpid falhou"); if(canceled) kill(pid,SIGTERM); }
    return WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);
}
static fs::path home() { const char* h=getenv("HOME"); if(!h || h[0]!='/') throw std::runtime_error("HOME absoluto necessário"); return h; }
static fs::path root() { return home()/".local/share/myct/downloads"; }
static bool valid_id(const std::string& s) { return std::regex_match(s,std::regex("[a-f0-9-]{36}")); }
static fs::path job(const std::string& id) { if(!valid_id(id)) throw std::runtime_error("ID inválido"); return root()/id; }
static bool public_ip(const sockaddr* sa) {
    if(sa->sa_family==AF_INET) {
        uint32_t a=ntohl(reinterpret_cast<const sockaddr_in*>(sa)->sin_addr.s_addr);
        for (auto [network, mask] : std::vector<std::pair<uint32_t,uint32_t>>{
            {0x00000000,0xff000000},{0x0a000000,0xff000000},{0x64400000,0xffc00000},
            {0x7f000000,0xff000000},{0xa9fe0000,0xffff0000},{0xac100000,0xfff00000},
            {0xc0000000,0xffffff00},{0xc0000200,0xffffff00},{0xc0a80000,0xffff0000},
            {0xc6120000,0xfffe0000},{0xc6336400,0xffffff00},{0xcb007100,0xffffff00}})
            if ((a & mask)==network) return false;
        return a < 0xe0000000;
    }
    if(sa->sa_family==AF_INET6) {
        const auto* a=reinterpret_cast<const sockaddr_in6*>(sa)->sin6_addr.s6_addr;
        // Only global unicast, excluding documentation prefix. IPv4-mapped is rejected.
        return (a[0]&0xe0)==0x20 && !(a[0]==0x20 && a[1]==2) &&
            !(a[0]==0x20 && a[1]==1 && (a[2]<2 || (a[2]==0x0d && a[3]==0xb8)));
    }
    return false;
}
struct Target { std::string resolve; };
static Target target(const std::string& url, bool private_ok) {
    if(url.size()>8192 || url.find_first_of("\r\n\t ")!=std::string::npos) throw std::runtime_error("URL inválida");
    std::smatch m;
    if(!std::regex_match(url,m,std::regex(R"(https?://(\[[0-9a-fA-F:]+\]|[A-Za-z0-9.-]+)(:[0-9]{1,5})?([/?#].*)?)")))
        throw std::runtime_error("Use URL http:// ou https:// sem credenciais e com host válido.");
    std::string host=m[1], port=m[2];
    if(host.front()=='[') host=host.substr(1,host.size()-2);
    port=port.empty()?(url.starts_with("https:")?"443":"80"):port.substr(1);
    if(std::stoi(port)<1 || std::stoi(port)>65535) throw std::runtime_error("Porta inválida");
    addrinfo hints{}, *addresses=nullptr; hints.ai_socktype=SOCK_STREAM;
    int rc=getaddrinfo(host.c_str(),port.c_str(),&hints,&addresses);
    if(rc) throw std::runtime_error("Falha DNS: "+std::string(gai_strerror(rc)));
    std::string ip;
    for(auto* a=addresses;a;a=a->ai_next) {
        if(a->ai_family!=AF_INET && a->ai_family!=AF_INET6) continue;
        if(!private_ok && !public_ip(a->ai_addr)) { freeaddrinfo(addresses); throw std::runtime_error("Destino não público bloqueado; para LAN própria use --allow-private explicitamente."); }
        char buf[INET6_ADDRSTRLEN];
        const void* src=a->ai_family==AF_INET?static_cast<void*>(&reinterpret_cast<sockaddr_in*>(a->ai_addr)->sin_addr):static_cast<void*>(&reinterpret_cast<sockaddr_in6*>(a->ai_addr)->sin6_addr);
        inet_ntop(a->ai_family,src,buf,sizeof(buf));
        if(!ip.empty()) ip+=",";
        ip+=a->ai_family==AF_INET6?"["s+buf+"]":buf;
    }
    freeaddrinfo(addresses);
    if(ip.empty()) throw std::runtime_error("DNS sem endereço utilizável");
    std::string curlhost=host.find(':')==std::string::npos?host:"["+host+"]";
    return {curlhost+":"+port+":"+ip};
}
static long long total(const fs::path& p) {
    std::ifstream f(p); std::string s; long long n=-1;
    while(std::getline(f,s)) {
        if(s.starts_with("HTTP/")) n=-1;
        std::transform(s.begin(),s.end(),s.begin(),[](unsigned char c){return std::tolower(c);});
        if(s.starts_with("content-length:")) { try { n=std::stoll(s.substr(15)); } catch(...) { n=-1; } }
        if(s.starts_with("transfer-encoding:")) n=-1;
    }
    return n;
}
static std::string unit(const fs::path& p) { return "myct-download-"+p.filename().string()+".service"; }
static bool active(const fs::path& p) { return run({"systemctl","--user","is-active","--quiet",unit(p)})==0; }
static void show(const fs::path& p) {
    std::string state=line(p/"state"), name=line(p/"name");
    if((state=="running" || state=="starting") && !active(p)) state="interrupted";
    fs::path dest=home()/"Downloads/My-CT"/p.filename()/name;
    std::error_code ec; auto size=fs::file_size(state=="completed"?dest:fs::path(dest.string()+".part"),ec);
    std::cout << "id: " << p.filename().string() << "\nstate: " << state << "\nbytes: " << (ec?0:size)
              << "\ntotal: " << total(p/"headers") << "\npath: " << dest.string() << (state=="completed"?"":".part")
              << "\nmessage: " << line(p/"message") << "\n";
}
static int worker(const fs::path& p) {
    struct sigaction sa{}; sa.sa_handler=stop; sigemptyset(&sa.sa_mask); sigaction(SIGTERM,&sa,nullptr); sigaction(SIGINT,&sa,nullptr);
    try {
        int lock=open((root()/"active.lock").c_str(),O_CREAT|O_RDWR|O_CLOEXEC,0600);
        if(lock<0 || flock(lock,LOCK_EX|LOCK_NB)) throw std::runtime_error("Outro download está ativo; aguarde ou cancele-o.");
        put(p/"state","running");
        auto dir=home()/"Downloads/My-CT"/p.filename(); fs::create_directories(dir);
        fs::path dest=dir/line(p/"name"), part=dest.string()+".part";
        int fd=open(part.c_str(),O_CREAT|O_EXCL|O_WRONLY|O_NOFOLLOW,0600);
        if(fd<0) throw std::runtime_error("Arquivo parcial já existe ou destino indisponível.");
        close(fd);
        std::string url=line(p/"url");
        for(int hop=0;hop<=5;++hop) {
            if(canceled) break;
            auto t=target(url,line(p/"private")=="yes");
            if(canceled) break;
            const auto available=fs::space(dir).available;
            if(available<16*1024*1024) throw std::runtime_error("Espaço insuficiente: menos de 16 MiB livres.");
            int rc=run({"/usr/bin/curl","-q","--silent","--show-error","--fail","--globoff","--proto","=http,https",
                "--proxy","","--noproxy","*","--resolve",t.resolve,"--connect-timeout","15","--speed-limit","1","--speed-time","30",
                "--max-time","86400","--max-filesize",std::to_string(available-16*1024*1024),
                "--output",part.string(),"--dump-header",(p/"headers").string(),"--write-out","%{http_code}\n%{redirect_url}\n","--url",url},p/"response",p/"curl-error");
            if(canceled) break;
            if(rc) {
                std::string reason= rc==22?"Falha HTTP":rc==28?"Timeout de conexão ou transferência sem progresso":(rc==23 || rc==128+SIGXFSZ)?"Falha ao gravar (disco cheio/permissões/limite de arquivo)":rc==63?"Arquivo excede o espaço disponível com reserva de 16 MiB":"Falha de rede/TLS/curl";
                throw std::runtime_error(reason+" (curl "+std::to_string(rc)+"). Consulte curl-error privado.");
            }
            std::ifstream r(p/"response"); std::string code,next; std::getline(r,code); std::getline(r,next);
            if(code.size()==3 && code[0]=='3' && !next.empty()) { url=next; if(hop==5) throw std::runtime_error("Limite de 5 redirecionamentos excedido"); continue; }
            if(code.size()!=3 || code[0]!='2') throw std::runtime_error("Resposta HTTP não concluída: "+code);
            if(canceled) break;
            // link is atomic and never replaces an existing file, unlike rename.
            if(link(part.c_str(),dest.c_str())) throw std::runtime_error("Não foi possível publicar arquivo; destino existente/disco/permissões.");
            fs::remove(part);
            put(p/"message","Download concluído"); put(p/"state","completed"); return 0;
        }
        put(p/"message","Cancelado; parcial preservado"); put(p/"state","canceled"); return 0;
    } catch(const std::exception& e) {
        put(p/"message",canceled?"Cancelado; parcial preservado":e.what()); put(p/"state",canceled?"canceled":"failed"); return 1;
    }
}
int main(int argc,char** argv) {
    umask(0077);
    try {
        if(argc==3 && std::string(argv[1])=="--worker") return worker(job(argv[2]));
        std::string cmd=argc>1?argv[1]:"";
        if(cmd=="start" && argc>=3 && argc<=5) {
            bool allow=false, named=false; std::string name="download";
            for(int i=3;i<argc;++i) { if(std::string(argv[i])=="--allow-private") allow=true; else if(!named) { name=argv[i]; named=true; } else throw std::runtime_error("Argumentos inválidos"); }
            if(!std::regex_match(name,std::regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) || name.ends_with(".part")) throw std::runtime_error("Nome inválido: use até 120 letras ASCII, números, ponto, _ ou -, sem .part final.");
            target(argv[2],allow);
            // Linger must already be configured by owner; never change login configuration.
            fs::create_directories(root());
            auto probe=root()/("linger-check-"+std::to_string(getpid()));
            bool linger=run({"loginctl","show-user",std::to_string(getuid()),"--property=Linger","--value"},probe)==0 && line(probe)=="yes";
            fs::remove(probe);
            if(!linger) throw std::runtime_error("Linger não está ativo; proprietário precisa habilitá-lo antes (ver documentação).");
            std::string id=line("/proc/sys/kernel/random/uuid"); auto p=job(id);
            if(!fs::create_directory(p)) throw std::runtime_error("Não foi possível reservar ID");
            put(p/"url",argv[2]); put(p/"name",name); put(p/"private",allow?"yes":"no"); put(p/"state","starting");
            auto exe=fs::canonical("/proc/self/exe");
            int rc=run({"systemd-run","--user","--quiet","--collect","--expand-environment=no","--service-type=exec","--unit="+unit(p),
                "--property=TimeoutStopSec=5","--property=UMask=0077","--property=StandardOutput=null","--property=StandardError=null",
                "--setenv=HOME="+home().string(),exe.string(),"--worker",id});
            if(rc) { put(p/"message","Falha ao iniciar systemd de usuário"); put(p/"state","failed"); throw std::runtime_error("systemd-run falhou; consulte status "+id); }
            std::cout<<id<<'\n'; return 0;
        }
        if(cmd=="list" && argc==2) {
            if(fs::exists(root())) for(const auto& e:fs::directory_iterator(root())) if(e.is_directory() && valid_id(e.path().filename())) { show(e.path()); std::cout<<'\n'; }
            return 0;
        }
        if((cmd=="status" || cmd=="cancel") && argc==3) {
            auto p=job(argv[2]); if(!fs::exists(p/"state")) throw std::runtime_error("Download desconhecido");
            if(cmd=="cancel" && active(p) && run({"systemctl","--user","stop",unit(p)})) throw std::runtime_error("Falha ao cancelar unidade");
            show(p); return 0;
        }
        std::cerr<<"Uso: myct-download start URL [nome] [--allow-private] | list | status ID | cancel ID\n"; return 2;
    } catch(const std::exception& e) { std::cerr<<"myct-download: "<<e.what()<<'\n'; return 1; }
}

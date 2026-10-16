// Internal worker: only fixed ClamAV options, one authorized inode, no shell.
#include "safe_file.hpp"
#include <sys/wait.h>
#include <sys/resource.h>
#include <sys/prctl.h>
#include <poll.h>
#include <signal.h>
#include <chrono>
#include <iostream>
#include <vector>

static volatile sig_atomic_t cancelled=0;
static void stop(int) { cancelled=1; }
int main(int argc, char** argv) {
    try {
        check(argc==2 || (argc==3 && std::string(argv[2])=="--watch-stdin"),"Uso interno: myct-av-scan PATH_HEX [--watch-stdin]");
        pid_t adapter=getppid();
        signal(SIGTERM,stop); signal(SIGINT,stop); signal(SIGHUP,stop);
        check(prctl(PR_SET_PDEATHSIG,SIGTERM)==0 && getppid()==adapter,"Adaptador desconectado.");
        Fd file{openHomeFile(decode(argv[1]),true)};
        struct stat before{},after{}; check(fstat(file.n,&before)==0,"stat falhou.");
        check(before.st_size<=64LL*1024*1024,"INCOMPLETE: limite de arquivo de 64 MiB.");
        std::string home=getpwuid(getuid())->pw_dir;
        // Parent adapter owns the locks and private temporary directory.
        std::string db=home+"/.local/share/myct/clamav-db";
        const char* tmp=getenv("MYCT_AV_TMP"); check(tmp,"Diretório temporário ausente.");
        int p[2]; check(pipe2(p,O_CLOEXEC)==0,"pipe falhou.");
        Fd input{p[0]},output{p[1]};
        auto start=std::chrono::steady_clock::now(); pid_t parent=getpid(),child=fork();
        check(child>=0,"fork falhou.");
        if(child==0) {
            prctl(PR_SET_PDEATHSIG,SIGKILL); if(getppid()!=parent) _exit(125);
            setpgid(0,0);
            dup2(file.n,0); dup2(output.n,1); dup2(output.n,2);
            rlimit mem{2ULL*1024*1024*1024,2ULL*1024*1024*1024},cpu{120,120},disk{256*1024*1024,256*1024*1024};
            if(setrlimit(RLIMIT_AS,&mem) || setrlimit(RLIMIT_CPU,&cpu) || setrlimit(RLIMIT_FSIZE,&disk)) _exit(125);
            nice(10);
            std::vector<std::string> args={"/usr/bin/clamscan","--database="+db,
                "--tempdir="+std::string(tmp),"--allmatch=yes","--alert-exceeds-max=yes",
                "--alert-encrypted=yes","--alert-broken=yes","--alert-broken-media=yes",
                "--max-filesize=64M","--max-scansize=128M","--max-files=1000",
                "--max-recursion=8","--max-scantime=0","--bytecode-timeout=1000",
                "--follow-dir-symlinks=0","--follow-file-symlinks=0","-"};
            std::vector<char*> av; for(auto& s:args) av.push_back(s.data()); av.push_back(nullptr);
            setenv("LC_ALL","C",1); execv(av[0],av.data()); _exit(127);
        }
        close(output.n); output.n=-1;
        fcntl(input.n,F_SETFL,O_NONBLOCK);
        int status=0; rusage usage{}; std::string log,reason; bool done=false;
        while(!done) {
            char b[4096]; ssize_t n;
            while((n=read(input.n,b,sizeof b))>0) {
                if(log.size()+n<=65536) log.append(b,n); else reason="Limite de saída atingido.";
            }
            if(cancelled) reason="Análise cancelada por sinal.";
            if(argc==3) {
                pollfd ev{0,POLLIN,0};
                if(poll(&ev,1,0)>0) {
                    char b[32];
                    if(ev.revents&(POLLHUP|POLLERR|POLLNVAL) || read(0,b,sizeof b)<=0)
                        reason="Cliente desconectado ou cancelou.";
                }
            }
            if(std::chrono::steady_clock::now()-start>std::chrono::seconds(180)) reason="Limite total de 180 segundos.";
            if(!reason.empty()) { kill(child,SIGKILL); }
            done=wait4(child,&status,WNOHANG,&usage)==child;
            if(!done) { pollfd ev{input.n,POLLIN,0}; poll(&ev,1,50); }
        }
        char b[4096]; ssize_t n;
        while((n=read(input.n,b,sizeof b))>0) {
            if(log.size()+n<=65536) log.append(b,n); else reason="Limite de saída atingido.";
        }
        if(fstat(file.n,&after)!=0 || before.st_size!=after.st_size ||
           before.st_mtim.tv_sec!=after.st_mtim.tv_sec || before.st_mtim.tv_nsec!=after.st_mtim.tv_nsec ||
           before.st_ctim.tv_sec!=after.st_ctim.tv_sec || before.st_ctim.tv_nsec!=after.st_ctim.tv_nsec ||
           before.st_atim.tv_sec!=after.st_atim.tv_sec || before.st_atim.tv_nsec!=after.st_atim.tv_nsec)
            reason="Arquivo mudou durante a análise; resultado incompleto.";
        auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
        auto seconds=[](timeval t) { return t.tv_sec+t.tv_usec/1e6; };
        std::cout<<"{\"exit_code\":"<<(WIFEXITED(status)?WEXITSTATUS(status):-1)
                 <<",\"signal\":"<<(WIFSIGNALED(status)?WTERMSIG(status):0)
                 <<",\"duration_ms\":"<<ms<<",\"peak_rss_kib\":"<<usage.ru_maxrss
                 <<",\"cpu_user_s\":"<<seconds(usage.ru_utime)<<",\"cpu_system_s\":"<<seconds(usage.ru_stime)
                 <<",\"incomplete_reason\":"<<json(reason)<<",\"engine_output\":"<<json(log)<<"}\n";
        return 0;
    } catch(const std::exception& e) {
        std::cout<<"{\"error\":"<<json(e.what())<<"}\n"; return 1;
    }
}

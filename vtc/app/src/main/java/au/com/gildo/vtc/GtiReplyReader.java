package au.com.gildo.vtc;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.function.LongSupplier;

/** Bounded receive only: never retransmits a command or substitutes a mismatched reply. */
final class GtiReplyReader {
    interface Receiver { String receive(int timeoutMs)throws IOException; }
    static int timeoutMs(String command) {
        // A field status reply took about 806 ms. Keep waiting on that original
        // read transaction rather than timing out at 700 ms and sending Stop.
        return "abefgjh".indexOf(command.charAt(1))>=0?1500:700;
    }
    static String expected(String command) {
        char op=command.charAt(1);
        if(op=='g')return "two hexadecimal high-speed-ratio digits";
        if(op=='f')return "three hexadecimal status digits";
        if("abejh".indexOf(op)>=0)return "six hexadecimal value digits";
        if("FGSIJKL".indexOf(op)>=0)return "empty acknowledgement";
        throw new IllegalArgumentException("Unsupported GTi command "+op);
    }
    static boolean matches(String command,String reply) {
        if(reply==null||!reply.endsWith("\r"))return false;
        // Explicit motor-controller errors must reach GtiClient immediately.
        if(reply.startsWith("!"))return true;
        char op=command.charAt(1);
        if(op=='g')return reply.matches("=[0-9A-Fa-f]{2}\r");
        if(op=='f')return reply.matches("=[0-9A-Fa-f]{3}\r");
        if("abejh".indexOf(op)>=0)return reply.matches("=[0-9A-Fa-f]{6}\r");
        if("FGSIJKL".indexOf(op)>=0)return reply.equals("=\r");
        return false;
    }
    static String receive(String command,Receiver receiver,LongSupplier clock)throws IOException {
        return receive(command,receiver,clock,timeoutMs(command));
    }
    static String receive(String command,Receiver receiver,LongSupplier clock,int budget)throws IOException {
        expected(command);
        int discarded=0;
        long end=clock.getAsLong()+budget;
        while(true) {
            long remaining=end-clock.getAsLong();
            if(remaining<=0)throw timeout(command,budget,discarded,null);
            String reply;
            try{reply=receiver.receive((int)remaining);}
            catch(SocketTimeoutException e){throw timeout(command,budget,discarded,e);}
            if(clock.getAsLong()>=end)throw timeout(command,budget,discarded,null);
            if(matches(command,reply))return reply;
            discarded++;
        }
    }
    private static SocketTimeoutException timeout(String command,int budget,int discarded,Exception cause) {
        SocketTimeoutException error=new SocketTimeoutException("GTi "+command.substring(1,3)+" reply timed out after "+budget+
            " ms; discarded "+discarded+" mismatched replies");
        if(cause!=null)error.initCause(cause);return error;
    }
}

package au.com.gildo.vtc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Explicit disconnect/exit completes after the bounded stop attempt, including failure. */
final class StopCompletion {
    private final List<Consumer<Boolean>> callbacks=new ArrayList<>();
    synchronized void request(Consumer<Boolean> callback){callbacks.add(callback);}
    synchronized boolean pending(){return !callbacks.isEmpty();}
    void complete(boolean confirmed){
        List<Consumer<Boolean>> ready;
        synchronized(this){ready=new ArrayList<>(callbacks);callbacks.clear();}
        for(Consumer<Boolean> callback:ready)callback.accept(confirmed);
    }
    static boolean recoveryCommand(String command){
        return command.matches(":[Lf][12]\\r");
    }
}

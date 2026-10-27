package io.github.robin9094707.simpleadminlite;

import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual Cumulus classes supplied inside the pinned official Geyser JAR. */
class NativeFormApiTest {
    @Test void realSimpleFormSupportsTheUsedBuilderAndCallbackShape() throws Exception {
        Class<?> form=Class.forName("org.geysermc.cumulus.form.SimpleForm");Object b=form.getMethod("builder").invoke(null);
        b=Crossplay.call(b,"title","Security dashboard");b=Crossplay.call(b,"content","English-only test");b=Crossplay.call(b,"button","Online players");
        b=Crossplay.call(b,"validResultHandler",(Consumer<Object>)r->{});assertNotNull(Crossplay.call(b,"build"));
    }
    @Test void realCustomFormSupportsTheUsedInputShape() throws Exception {
        Class<?> form=Class.forName("org.geysermc.cumulus.form.CustomForm");Object b=form.getMethod("builder").invoke(null);
        b=Crossplay.call(b,"title","Ban player");b=Crossplay.call(b,"input","Duration","","1h");b=Crossplay.call(b,"validResultHandler",(Consumer<Object>)r->{});assertNotNull(Crossplay.call(b,"build"));
    }
}

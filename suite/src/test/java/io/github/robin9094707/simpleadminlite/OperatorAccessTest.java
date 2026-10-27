package io.github.robin9094707.simpleadminlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OperatorAccessTest {
    @TempDir Path temp;
    UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Test void onlyFullLevelFourOperatorsReceiveAutomaticAccess() throws Exception {
        Path file=temp.resolve("ops.json");Files.writeString(file,"[{\"uuid\":\""+id+"\",\"level\":3}]");OperatorAccess access=new OperatorAccess(file);assertFalse(access.full(id));
        Files.writeString(file,"[{\"uuid\":\""+id+"\",\"level\":4,\"name\":\"FullOp\"}]");assertTrue(access.full(id));assertFalse(access.full(UUID.randomUUID()));
    }
    @Test void unreadableOrMalformedOperatorListsDoNotGrantAccess() throws Exception {
        Path file=temp.resolve("ops.json");OperatorAccess access=new OperatorAccess(file);assertFalse(access.full(id));Files.writeString(file,"invalid");assertFalse(access.full(id));
    }
}

package com.protocol7.quincy.protocol.packets;

import com.protocol7.quincy.protocol.ConnectionId;
import com.protocol7.quincy.protocol.Version;
import com.protocol7.quincy.tls.aead.AEADProvider;
import java.util.Optional;

public interface HalfParsedPacket<P extends Packet> {

  Optional<Version> getVersion();

  Optional<ConnectionId> getConnectionId();

  P complete(AEADProvider aeadProvider);
}

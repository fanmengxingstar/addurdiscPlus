package nya.tuyw.addurdisc.disc.net;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.FriendlyByteBuf;
import java.util.function.BiConsumer;
public final class LegacyServerNetworking {
    public record Context(MinecraftServer server, ServerPlayer player) { }
    public static <T extends DiscPayloads.Payload> void registerGlobalReceiver(DiscPayloads.Type<T> type, BiConsumer<T,Context> handler) {
        ServerPlayNetworking.registerGlobalReceiver(type.id(),(server,player,listener,buffer,sender)-> {
            T value = type.read(buffer);
            server.execute(()->handler.accept(value,new Context(server,player)));
        });
    }
    public static boolean canSend(ServerPlayer player,DiscPayloads.Type<?> type) { return ServerPlayNetworking.canSend(player,type.id()); }
    public static void send(ServerPlayer player,DiscPayloads.Payload payload) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        payload.type().write(buffer,payload);
        ServerPlayNetworking.send(player,payload.type().id(),buffer);
    }
    private LegacyServerNetworking() { }
}

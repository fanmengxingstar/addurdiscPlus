package nya.tuyw.addurdisc.disc.net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import java.util.function.BiConsumer;
public final class LegacyClientNetworking {
    public record Context(Minecraft client) { }
    public static <T extends DiscPayloads.Payload> void registerGlobalReceiver(DiscPayloads.Type<T> type, BiConsumer<T,Context> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type.id(),(client,listener,buffer,sender)-> {
            T value = type.read(buffer);
            client.execute(()->handler.accept(value,new Context(client)));
        });
    }
    public static boolean canSend(DiscPayloads.Type<?> type) { return ClientPlayNetworking.canSend(type.id()); }
    public static void send(DiscPayloads.Payload payload) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        payload.type().write(buffer,payload);
        ClientPlayNetworking.send(payload.type().id(),buffer);
    }
    private LegacyClientNetworking() { }
}

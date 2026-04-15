import { Feather } from "@expo/vector-icons";
import * as Haptics from "expo-haptics";
import { useRouter } from "expo-router";
import { KeyboardAvoidingView } from "react-native-keyboard-controller";
import React, { useRef, useState } from "react";
import {
  FlatList,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { MessageBubble } from "@/components/MessageBubble";
import { NexoStatusBadge } from "@/components/NexoStatusBadge";
import { useBLE } from "@/contexts/BLEContext";
import { useColors } from "@/hooks/useColors";

export default function ChatScreen() {
  const colors = useColors();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const bottomPad = Platform.OS === "web" ? 34 : insets.bottom;
  const topPad = Platform.OS === "web" ? 67 : insets.top;

  const { connectedPeer, messages, deviceStatus, disconnectFromPeer, sendMessage } = useBLE();
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const listRef = useRef<FlatList>(null);

  const peerMessages = connectedPeer
    ? messages.filter((m) => m.peerId === connectedPeer.id)
    : [];

  async function handleSend() {
    if (!text.trim() || sending) return;
    const content = text.trim();
    setText("");
    setSending(true);
    if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    await sendMessage(content);
    setSending(false);
    setTimeout(() => listRef.current?.scrollToEnd({ animated: true }), 100);
  }

  function handleDisconnect() {
    disconnectFromPeer();
    router.back();
  }

  if (!connectedPeer) {
    return (
      <View style={[styles.noConn, { backgroundColor: colors.background }]}>
        <Feather name="bluetooth-off" size={40} color={colors.mutedForeground} />
        <Text style={[styles.noConnText, { color: colors.mutedForeground }]}>
          Sin conexión activa
        </Text>
        <Pressable
          style={[styles.backBtn, { backgroundColor: colors.primary }]}
          onPress={() => router.back()}
        >
          <Text style={[styles.backBtnText, { color: colors.primaryForeground }]}>Volver</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <View
        style={[
          styles.header,
          { backgroundColor: colors.background, borderBottomColor: colors.border, paddingTop: topPad + 8 },
        ]}
      >
        <Pressable style={styles.backPress} onPress={() => router.back()}>
          <Feather name="chevron-left" size={24} color={colors.foreground} />
        </Pressable>
        <View style={styles.headerCenter}>
          <Text style={[styles.headerTitle, { color: colors.foreground }]}>{connectedPeer.name}</Text>
          <View style={styles.headerSub}>
            <NexoStatusBadge status={deviceStatus} />
            {connectedPeer.trusted && (
              <View style={styles.trustedPill}>
                <Feather name="shield" size={10} color="#10b981" />
                <Text style={styles.trustedPillText}>Confianza</Text>
              </View>
            )}
          </View>
        </View>
        <Pressable
          style={[styles.disconnectBtn, { backgroundColor: `${colors.destructive}22` }]}
          onPress={handleDisconnect}
        >
          <Feather name="x" size={18} color={colors.destructive} />
        </Pressable>
      </View>

      {connectedPeer.publicKeyFingerprint && (
        <View style={[styles.encBar, { backgroundColor: "#10b98115", borderBottomColor: "#10b98133" }]}>
          <Feather name="lock" size={11} color="#10b981" />
          <Text style={[styles.encBarText, { color: "#10b981" }]}>
            Cifrado E2E · {connectedPeer.publicKeyFingerprint}
          </Text>
        </View>
      )}

      <KeyboardAvoidingView
        style={{ flex: 1 }}
        behavior="padding"
        keyboardVerticalOffset={0}
      >
        <FlatList
          ref={listRef}
          data={peerMessages}
          keyExtractor={(m) => m.id}
          renderItem={({ item }) => <MessageBubble message={item} />}
          contentContainerStyle={[styles.msgList, { paddingTop: 10, paddingBottom: 10 }]}
          ListEmptyComponent={
            <View style={styles.emptyChat}>
              <Feather name="message-circle" size={30} color={colors.mutedForeground} />
              <Text style={[styles.emptyChatText, { color: colors.mutedForeground }]}>
                Canal seguro establecido
              </Text>
              <Text style={[styles.emptyChatSub, { color: colors.mutedForeground }]}>
                Los mensajes son cifrados E2E de extremo a extremo
              </Text>
            </View>
          }
          onContentSizeChange={() => listRef.current?.scrollToEnd({ animated: false })}
          keyboardShouldPersistTaps="handled"
        />

        <View
          style={[
            styles.inputContainer,
            {
              backgroundColor: colors.background,
              borderTopColor: colors.border,
              paddingBottom: bottomPad + 8,
            },
          ]}
        >
          <View
            style={[
              styles.inputRow,
              { backgroundColor: colors.card, borderColor: colors.border },
            ]}
          >
            <TextInput
              style={[styles.input, { color: colors.foreground }]}
              placeholder="Mensaje cifrado..."
              placeholderTextColor={colors.mutedForeground}
              value={text}
              onChangeText={setText}
              multiline
              maxLength={512}
              returnKeyType="send"
              blurOnSubmit={false}
              onSubmitEditing={handleSend}
            />
            <Pressable
              style={[
                styles.sendBtn,
                {
                  backgroundColor: text.trim() ? colors.primary : colors.secondary,
                  opacity: sending ? 0.6 : 1,
                },
              ]}
              onPress={handleSend}
              disabled={!text.trim() || sending}
            >
              <Feather
                name="send"
                size={17}
                color={text.trim() ? colors.primaryForeground : colors.mutedForeground}
              />
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  noConn: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    gap: 16,
  },
  noConnText: { fontSize: 16, fontWeight: "600" },
  backBtn: {
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 10,
    marginTop: 8,
  },
  backBtnText: { fontSize: 15, fontWeight: "700" },
  header: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 12,
    paddingBottom: 12,
    borderBottomWidth: 1,
    gap: 8,
  },
  backPress: {
    padding: 6,
  },
  headerCenter: { flex: 1 },
  headerTitle: { fontSize: 16, fontWeight: "700" },
  headerSub: { flexDirection: "row", alignItems: "center", gap: 6, marginTop: 3 },
  trustedPill: {
    flexDirection: "row",
    alignItems: "center",
    gap: 3,
    backgroundColor: "#10b98122",
    paddingHorizontal: 7,
    paddingVertical: 3,
    borderRadius: 10,
  },
  trustedPillText: { fontSize: 10, fontWeight: "700", color: "#10b981" },
  disconnectBtn: {
    width: 36,
    height: 36,
    borderRadius: 10,
    alignItems: "center",
    justifyContent: "center",
  },
  encBar: {
    flexDirection: "row",
    alignItems: "center",
    gap: 7,
    paddingHorizontal: 16,
    paddingVertical: 7,
    borderBottomWidth: 1,
  },
  encBarText: {
    fontSize: 11,
    fontWeight: "600",
    fontFamily: "monospace",
    letterSpacing: 0.3,
  },
  msgList: {
    gap: 2,
  },
  emptyChat: {
    alignItems: "center",
    justifyContent: "center",
    paddingTop: 60,
    gap: 10,
  },
  emptyChatText: { fontSize: 15, fontWeight: "600" },
  emptyChatSub: { fontSize: 12, textAlign: "center", paddingHorizontal: 40 },
  inputContainer: {
    borderTopWidth: 1,
    paddingHorizontal: 12,
    paddingTop: 8,
  },
  inputRow: {
    flexDirection: "row",
    alignItems: "flex-end",
    borderWidth: 1,
    borderRadius: 14,
    overflow: "hidden",
    gap: 0,
  },
  input: {
    flex: 1,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 14,
    maxHeight: 100,
  },
  sendBtn: {
    width: 44,
    height: 44,
    margin: 4,
    borderRadius: 10,
    alignItems: "center",
    justifyContent: "center",
  },
});

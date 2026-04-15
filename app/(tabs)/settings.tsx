import { Feather } from "@expo/vector-icons";
import * as Haptics from "expo-haptics";
import React, { useState } from "react";
import {
  Alert,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { NexoHeader } from "@/components/NexoHeader";
import { useBLE } from "@/contexts/BLEContext";
import { useColors } from "@/hooks/useColors";

const NAP_CODES: { code: string; desc: string; severity: "info" | "warn" | "error" }[] = [
  { code: "NAP_001", desc: "Fallo al verificar estado Bluetooth", severity: "error" },
  { code: "NAP_002", desc: "Permisos Bluetooth denegados", severity: "error" },
  { code: "NAP_003", desc: "Timeout de conexión GATT", severity: "warn" },
  { code: "NAP_004", desc: "Error de fragmentación de mensaje (chunk)", severity: "warn" },
  { code: "NAP_005", desc: "Fallo de handshake criptográfico", severity: "error" },
  { code: "NAP_006", desc: "Peer no confiable — verificación rechazada", severity: "warn" },
  { code: "NAP_007", desc: "Canal BLE desconectado inesperadamente", severity: "warn" },
  { code: "NAP_008", desc: "MTU negociación fallida", severity: "info" },
];

function SeverityColor(s: "info" | "warn" | "error") {
  if (s === "error") return "#ef4444";
  if (s === "warn") return "#f59e0b";
  return "#3b82f6";
}

function SettingRow({
  icon,
  label,
  value,
  onPress,
  danger,
}: {
  icon: string;
  label: string;
  value?: string;
  onPress?: () => void;
  danger?: boolean;
}) {
  const colors = useColors();
  return (
    <Pressable
      style={({ pressed }) => [
        styles.row,
        { backgroundColor: colors.card, borderColor: colors.border, opacity: pressed ? 0.7 : 1 },
      ]}
      onPress={onPress}
    >
      <Feather name={icon as any} size={18} color={danger ? colors.destructive : colors.primary} />
      <Text style={[styles.rowLabel, { color: danger ? colors.destructive : colors.foreground }]}>
        {label}
      </Text>
      {value !== undefined && (
        <Text style={[styles.rowValue, { color: colors.mutedForeground }]}>{value}</Text>
      )}
      {onPress && (
        <Feather name="chevron-right" size={16} color={colors.mutedForeground} />
      )}
    </Pressable>
  );
}

export default function SettingsScreen() {
  const colors = useColors();
  const insets = useSafeAreaInsets();
  const bottomPad = Platform.OS === "web" ? 34 : insets.bottom;
  const { myDeviceName, setMyDeviceName, peers, clearMessages, deviceStatus } = useBLE();
  const [editingName, setEditingName] = useState(false);
  const [nameInput, setNameInput] = useState(myDeviceName);

  async function saveDeviceName() {
    const trimmed = nameInput.trim();
    if (!trimmed) return;
    await setMyDeviceName(trimmed);
    setEditingName(false);
    if (Platform.OS !== "web") Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
  }

  function handleClearMessages() {
    if (Platform.OS === "web") {
      clearMessages();
      return;
    }
    Alert.alert(
      "Limpiar historial",
      "¿Eliminar todos los mensajes? Esta acción no puede deshacerse.",
      [
        { text: "Cancelar", style: "cancel" },
        {
          text: "Eliminar",
          style: "destructive",
          onPress: () => {
            clearMessages();
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
          },
        },
      ]
    );
  }

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <NexoHeader title="Configuración" status={deviceStatus} />

      <ScrollView
        contentContainerStyle={[styles.content, { paddingBottom: bottomPad + 90 }]}
        showsVerticalScrollIndicator={false}
      >
        <Text style={[styles.sectionTitle, { color: colors.mutedForeground }]}>
          IDENTIDAD DEL DISPOSITIVO
        </Text>

        {editingName ? (
          <View style={[styles.editCard, { backgroundColor: colors.card, borderColor: colors.border }]}>
            <TextInput
              style={[styles.nameInput, { color: colors.foreground, borderColor: colors.border, backgroundColor: colors.secondary }]}
              value={nameInput}
              onChangeText={setNameInput}
              autoFocus
              maxLength={24}
              placeholder="Nombre del dispositivo"
              placeholderTextColor={colors.mutedForeground}
            />
            <View style={styles.editActions}>
              <Pressable
                style={[styles.editBtn, { backgroundColor: colors.secondary }]}
                onPress={() => setEditingName(false)}
              >
                <Text style={[styles.editBtnText, { color: colors.foreground }]}>Cancelar</Text>
              </Pressable>
              <Pressable
                style={[styles.editBtn, { backgroundColor: colors.primary }]}
                onPress={saveDeviceName}
              >
                <Text style={[styles.editBtnText, { color: colors.primaryForeground }]}>Guardar</Text>
              </Pressable>
            </View>
          </View>
        ) : (
          <SettingRow
            icon="smartphone"
            label="Nombre del dispositivo"
            value={myDeviceName}
            onPress={() => setEditingName(true)}
          />
        )}

        <Text style={[styles.sectionTitle, { color: colors.mutedForeground }]}>
          BLE & SEGURIDAD
        </Text>

        <SettingRow
          icon="users"
          label="Peers conocidos"
          value={String(peers.length)}
        />
        <SettingRow
          icon="shield"
          label="Peers de confianza"
          value={String(peers.filter((p) => p.trusted).length)}
        />
        <SettingRow
          icon="lock"
          label="Cifrado"
          value="CryptoVault v9.7"
        />
        <SettingRow
          icon="layers"
          label="Protocolo"
          value="NAP BLE 3.3"
        />
        <SettingRow
          icon="cpu"
          label="MTU máximo"
          value="512 bytes"
        />

        <Text style={[styles.sectionTitle, { color: colors.mutedForeground }]}>
          GESTIÓN
        </Text>

        <SettingRow
          icon="trash-2"
          label="Limpiar historial de mensajes"
          onPress={handleClearMessages}
          danger
        />

        <Text style={[styles.sectionTitle, { color: colors.mutedForeground }]}>
          CÓDIGOS DE ERROR NAP
        </Text>

        <View style={[styles.napTable, { backgroundColor: colors.card, borderColor: colors.border }]}>
          {NAP_CODES.map((nap, i) => (
            <View
              key={nap.code}
              style={[
                styles.napRow,
                i < NAP_CODES.length - 1 && { borderBottomWidth: 1, borderBottomColor: colors.border },
              ]}
            >
              <View
                style={[
                  styles.napCode,
                  { backgroundColor: `${SeverityColor(nap.severity)}22` },
                ]}
              >
                <Text style={[styles.napCodeText, { color: SeverityColor(nap.severity) }]}>
                  {nap.code}
                </Text>
              </View>
              <Text style={[styles.napDesc, { color: colors.mutedForeground }]}>{nap.desc}</Text>
            </View>
          ))}
        </View>

        <Text style={[styles.version, { color: colors.mutedForeground }]}>
          NEXO v3.3.0-NAP · Capacitor 6 · Android 14+ (API 34)
        </Text>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  content: { padding: 16, gap: 8 },
  sectionTitle: {
    fontSize: 11,
    fontWeight: "700",
    letterSpacing: 1,
    marginTop: 12,
    marginBottom: 4,
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    padding: 14,
    borderRadius: 12,
    borderWidth: 1,
    marginBottom: 0,
  },
  rowLabel: {
    flex: 1,
    fontSize: 14,
    fontWeight: "500",
  },
  rowValue: {
    fontSize: 13,
  },
  editCard: {
    borderRadius: 12,
    borderWidth: 1,
    padding: 14,
    gap: 12,
  },
  nameInput: {
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
  },
  editActions: {
    flexDirection: "row",
    gap: 10,
  },
  editBtn: {
    flex: 1,
    alignItems: "center",
    paddingVertical: 10,
    borderRadius: 8,
  },
  editBtnText: {
    fontWeight: "700",
    fontSize: 14,
  },
  napTable: {
    borderRadius: 12,
    borderWidth: 1,
    overflow: "hidden",
  },
  napRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    padding: 12,
  },
  napCode: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6,
  },
  napCodeText: {
    fontSize: 11,
    fontWeight: "700",
    fontFamily: "monospace",
  },
  napDesc: {
    flex: 1,
    fontSize: 12,
  },
  version: {
    fontSize: 11,
    textAlign: "center",
    marginTop: 12,
    letterSpacing: 0.5,
  },
});

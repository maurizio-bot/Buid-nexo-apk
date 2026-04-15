import { Feather } from "@expo/vector-icons";
import React from "react";
import { StyleSheet, Text, View } from "react-native";
import { useColors } from "@/hooks/useColors";
import { NexoMessage } from "@/contexts/BLEContext";

interface Props {
  message: NexoMessage;
  showTime?: boolean;
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  const h = d.getHours().toString().padStart(2, "0");
  const m = d.getMinutes().toString().padStart(2, "0");
  const s = d.getSeconds().toString().padStart(2, "0");
  return `${h}:${m}:${s}`;
}

export function MessageBubble({ message, showTime = true }: Props) {
  const colors = useColors();
  const isOut = message.direction === "outbound";

  return (
    <View style={[styles.wrapper, isOut ? styles.wrapperOut : styles.wrapperIn]}>
      <View
        style={[
          styles.bubble,
          isOut
            ? [styles.bubbleOut, { backgroundColor: colors.primary }]
            : [styles.bubbleIn, { backgroundColor: colors.card, borderColor: colors.border }],
        ]}
      >
        <Text
          style={[
            styles.content,
            { color: isOut ? colors.primaryForeground : colors.foreground },
          ]}
        >
          {message.content}
        </Text>

        <View style={styles.meta}>
          {message.encrypted && (
            <View style={styles.encBadge}>
              <Feather
                name="lock"
                size={9}
                color={isOut ? `${colors.primaryForeground}88` : colors.mutedForeground}
              />
              <Text
                style={[
                  styles.encText,
                  { color: isOut ? `${colors.primaryForeground}88` : colors.mutedForeground },
                ]}
              >
                E2E
              </Text>
            </View>
          )}
          {showTime && (
            <Text
              style={[
                styles.time,
                { color: isOut ? `${colors.primaryForeground}88` : colors.mutedForeground },
              ]}
            >
              {formatTime(message.timestamp)}
            </Text>
          )}
          {isOut && (
            <Feather
              name={message.delivered ? "check-circle" : "clock"}
              size={11}
              color={message.delivered ? `${colors.primaryForeground}88` : `${colors.primaryForeground}55`}
            />
          )}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrapper: {
    marginVertical: 2,
    paddingHorizontal: 12,
  },
  wrapperOut: {
    alignItems: "flex-end",
  },
  wrapperIn: {
    alignItems: "flex-start",
  },
  bubble: {
    maxWidth: "78%",
    borderRadius: 16,
    padding: 10,
    paddingHorizontal: 14,
    gap: 4,
  },
  bubbleOut: {
    borderBottomRightRadius: 4,
  },
  bubbleIn: {
    borderWidth: 1,
    borderBottomLeftRadius: 4,
  },
  content: {
    fontSize: 14,
    lineHeight: 20,
  },
  meta: {
    flexDirection: "row",
    alignItems: "center",
    gap: 5,
    justifyContent: "flex-end",
  },
  encBadge: {
    flexDirection: "row",
    alignItems: "center",
    gap: 2,
  },
  encText: {
    fontSize: 9,
    fontWeight: "700",
    letterSpacing: 0.5,
  },
  time: {
    fontSize: 10,
    fontFamily: "monospace",
  },
});

import Anthropic from "@anthropic-ai/sdk";

interface Env {
  ANTHROPIC_API_KEY: string;
  APP_TOKEN: string;
}

// Bối cảnh cố định cho Claude (giữ nguyên từng byte để prompt cache hoạt động).
const SYSTEM = `Bạn là kỹ sư phân tích nhật ký cho MotoNav, app dẫn đường Android do anh Hiệp tự làm cho xe Yamaha NMAX.
App vẽ bản đồ thành ảnh JPEG 480x234 và gửi qua Bluetooth RFCOMM lên màn TFT của xe.

Giao thức đã biết:
- Mỗi khung JPEG phải được xe trả ACK (cmd 0x50) trước khi gửi khung kế (1 khung đang bay).
- Xe gửi cmd 0x41 (65) khoảng 1 lần/giây, byte 1 = tốc độ km/h. Mất 0x41 > 6 s thì app coi kết nối chết và nối lại (tftDeadReconnect).
- Xe gửi 56 = tạm dừng, 55 v=1 = tiếp tục, 49 = dừng. App gửi cmd 2 = bật/tắt chế độ dẫn đường (tftNavMode), cmd 3 = tên điểm đến (tftNameTx).
- "Đứng hình" = tổng thời gian các khoảng trống giữa hai ACK > 3 s chia cho thời gian chuyến. Mục tiêu dưới 5%.
- Các lần khựng thường dài 4–6 s, hay rơi vào nhịp 5/10 s; 0x41 cũng thưa đi (tft41Gap ≥ 1,6 s) trong lúc khựng.

Đã loại trừ (đừng đề xuất lại nếu không có bằng chứng mới trong nhật ký): nhiệt điện thoại, bộ nhớ app, kích thước ảnh, nhịp gửi 10–30 ms/KB, mạng 4G/5G, Wi-Fi, chế độ tiết kiệm pin, chiếc điện thoại (Pixel 5 cũng bị như OPPO). Anh Hiệp không chấp nhận đổ lỗi phần cứng hay nhiễu Bluetooth nếu không có số liệu chứng minh.
Gói tên điểm đến từng gây lag nặng; chỉ được gửi 1 lần mỗi kết nối.

Cách trả lời:
- Tiếng Việt, xưng "em", gọi "Sếp". Ngắn gọn, đi thẳng vào kết quả.
- Mở đầu: 1–2 câu kết luận chuyến này tốt hay xấu, kèm % đứng hình và số lần rớt kết nối.
- Sau đó tối đa 5 gạch đầu dòng: lag tập trung ở đâu (giờ, phút), trùng với sự kiện gì, so với mục tiêu.
- Mỗi nhận định phải dẫn số liệu hoặc giờ cụ thể trong bản tóm tắt. Không đoán khi không có dữ liệu; nói rõ "chưa đủ dữ liệu".
- Cuối cùng: 1 đề xuất thử nghiệm cụ thể, đo được, cho chuyến tới.
- Nếu Sếp có câu hỏi thêm, trả lời câu hỏi đó trước.`;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (req.method === "GET" && url.pathname === "/") return json({ ok: true });
    if (req.method !== "POST" || url.pathname !== "/analyze") return json({ error: "not found" }, 404);
    if (!env.APP_TOKEN || req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);

    let body: { digest?: string; question?: string; app?: string };
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad json" }, 400);
    }
    const digest = (body.digest ?? "").slice(0, 400_000);
    if (!digest) return json({ error: "empty digest" }, 400);

    let userText = `Bản app: ${body.app ?? "?"}\n\n${digest}`;
    if (body.question) userText += `\n\nCÂU HỎI THÊM CỦA SẾP: ${body.question.slice(0, 2000)}`;

    const client = new Anthropic({ apiKey: env.ANTHROPIC_API_KEY });
    try {
      const stream = client.beta.messages.stream({
        model: "claude-opus-5-5",
        max_tokens: 16000,
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
        output_config: { effort: "high" },
        system: [{ type: "text", text: SYSTEM, cache_control: { type: "ephemeral" } }],
        messages: [{ role: "user", content: userText }],
      } as any);
      const msg: any = await stream.finalMessage();
      if (msg.stop_reason === "refusal") return json({ text: "Claude từ chối phân tích yêu cầu này." });
      const text = (msg.content as any[])
        .filter((b) => b.type === "text")
        .map((b) => b.text)
        .join("\n")
        .trim();
      return json({ text: text || "(không có nội dung)", usage: msg.usage });
    } catch (e) {
      if (e instanceof Anthropic.RateLimitError) return json({ error: "Claude đang quá tải, thử lại sau ít phút." }, 429);
      if (e instanceof Anthropic.AuthenticationError) return json({ error: "Khoá Anthropic sai hoặc hết hạn." }, 502);
      if (e instanceof Anthropic.APIError) return json({ error: `Lỗi Claude ${e.status}: ${e.message}` }, 502);
      return json({ error: String(e) }, 500);
    }
  },
};

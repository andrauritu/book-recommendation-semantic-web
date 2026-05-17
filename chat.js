(function () {
    const page = document.body.dataset.page || "list";
    const bookId = document.body.dataset.bookId || new URLSearchParams(location.search).get("id") || "";

    const root = document.createElement("div");
    root.className = "chatbot";
    root.innerHTML = `
        <button class="chatbot-toggle" type="button" title="Open chat">?</button>
        <section class="chatbot-window">
            <div class="chatbot-header">
                <span class="chatbot-title">Book Assistant</span>
                <button class="chatbot-close" type="button" title="Close">x</button>
            </div>
            <div class="chatbot-starters"></div>
            <div class="chatbot-messages"></div>
            <form class="chatbot-form">
                <input class="chatbot-input" placeholder="Ask about books..." required>
                <button class="chatbot-send" type="submit">Send</button>
            </form>
        </section>
    `;
    document.body.append(root);

    const startersBox = root.querySelector(".chatbot-starters");
    const messages = root.querySelector(".chatbot-messages");
    const input = root.querySelector(".chatbot-input");

    root.querySelector(".chatbot-toggle").addEventListener("click", () => root.classList.add("open"));
    root.querySelector(".chatbot-close").addEventListener("click", () => root.classList.remove("open"));
    root.querySelector(".chatbot-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const text = input.value.trim();

        if (!text) {
            return;
        }

        input.value = "";
        addMessage(text, "user");
        addMessage("Thinking...", "bot");
        const loading = messages.lastElementChild;

        const response = await fetch("/api/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ message: text, page, bookId })
        });
        const data = await response.json();
        loading.textContent = data.answer;
    });

    loadStarters();

    async function loadStarters() {
        const response = await fetch(`/api/chat/starters?page=${encodeURIComponent(page)}&bookId=${encodeURIComponent(bookId)}`);
        const data = await response.json();
        startersBox.replaceChildren();

        for (const starter of data.starters) {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = starter;
            button.addEventListener("click", () => {
                root.classList.add("open");
                input.value = starter;
                input.focus();
            });
            startersBox.append(button);
        }
    }

    function addMessage(text, type) {
        const message = document.createElement("div");
        message.className = `chatbot-message ${type}`;
        message.textContent = text;
        messages.append(message);
        messages.scrollTop = messages.scrollHeight;
    }
})();

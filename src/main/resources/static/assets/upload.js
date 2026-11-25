for (const box of document.querySelectorAll("[data-drop-wrap]")) {
    const dropzone = box.querySelector("[data-dropzone]");
    const input = box.querySelector("input");
    const filename = box.querySelector("[data-file-name]");
    const hint = box.querySelector("[data-file-hint]");
    const clear = box.querySelector("[data-clear-file]");
    const status = box.querySelector("[data-file-status]");
    const numberFormat = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 1 });

    function displayFile() {
        const file = input.files[0];
        box.classList.toggle("has-file", Boolean(file));
        clear.hidden = !file;
        filename.textContent = file ? file.name : "Выбрать файл";
        hint.textContent = file
            ? numberFormat.format(file.size / 1024) + " КБ"
            : "или перетащите сюда";
        status.textContent = file ? "Выбран файл " + file.name : "Файл не выбран";
    }

    box.classList.add("enhanced");
    input.addEventListener("change", displayFile);
    clear.addEventListener("click", () => {
        input.value = "";
        input.focus();
        displayFile();
    });
    dropzone.addEventListener("dragover", event => {
        if (!event.dataTransfer.types.includes("Files")) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = "copy";
        dropzone.classList.add("drag-over");
    });
    dropzone.addEventListener("dragleave", event => {
        if (!dropzone.contains(event.relatedTarget)) {
            dropzone.classList.remove("drag-over");
        }
    });
    dropzone.addEventListener("drop", event => {
        event.preventDefault();
        dropzone.classList.remove("drag-over");
        const [file, additionalFile] = event.dataTransfer.files;
        if (!file) return;
        if (additionalFile) {
            status.textContent = "Перетащите по одному файлу в каждую область";
            hint.textContent = status.textContent;
            return;
        }
        const files = new DataTransfer();
        files.items.add(file);
        input.files = files.files;
        displayFile();
    });
    window.addEventListener("pageshow", displayFile);
}

"use client";

import { FormEvent, useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { MediaItem } from "@/lib/types";

export function Dashboard() {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [token, setToken] = useState<string | null>(null);
  const [message, setMessage] = useState("");

  useEffect(() => {
    async function loadCatalog() {
      setToken(window.localStorage.getItem("umt_token"));
      try {
        setItems(await api.listMedia());
      } catch (error) {
        setMessage(error instanceof Error ? error.message : "No se pudo cargar el catálogo");
      }
    }
    void loadCatalog();
  }, []);

  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    try {
      const result = await api.register(
        String(form.get("email")),
        String(form.get("displayName")),
        String(form.get("password")),
      );
      window.localStorage.setItem("umt_token", result.access_token);
      setToken(result.access_token);
      setMessage("Cuenta creada. Tu biblioteca ya está sincronizada.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "No fue posible crear la cuenta");
    }
  }

  async function track(item: MediaItem) {
    if (!token) {
      setMessage("Crea una cuenta para guardar elementos.");
      return;
    }
    try {
      await api.track(token, item.id);
      setMessage(`${item.title} se añadió a tu lista.`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "No fue posible guardar el elemento");
    }
  }

  return (
    <section className="dashboard shell" id="dashboard">
      <div className="sectionTitle">
        <div><p className="eyebrow">Catálogo unificado</p><h2>Explora y continúa</h2></div>
        <span className="livePill">Sincronización activa</span>
      </div>
      {message && <p className="notice" role="status">{message}</p>}
      {!token && (
        <form className="signup" onSubmit={register}>
          <input name="displayName" placeholder="Tu nombre" minLength={1} required />
          <input name="email" type="email" placeholder="correo@ejemplo.com" required />
          <input name="password" type="password" placeholder="Contraseña (10+ caracteres)" minLength={10} required />
          <button type="submit">Crear cuenta</button>
        </form>
      )}
      <div className="mediaGrid">
        {items.length === 0 ? (
          <div className="emptyState">
            <span>＋</span><h3>Tu catálogo está listo para crecer</h3>
            <p>Agrega contenido desde la API y aparecerá aquí en Web y Android.</p>
          </div>
        ) : items.map((item) => (
          <article className="mediaCard" key={item.id}>
            <span className="mediaType">{item.media_type}</span>
            <h3>{item.title}</h3>
            <p>{item.release_year ?? "Sin fecha"}</p>
            <button onClick={() => track(item)}>＋ Mi lista</button>
          </article>
        ))}
      </div>
    </section>
  );
}

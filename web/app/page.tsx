'use client';

import { useEffect, useState } from 'react';
import { Dashboard } from '@/components/dashboard';

export default function Home() {
  // authState: 'loading' (SSR / primer render) | 'guest' | 'user'
  const [authState, setAuthState] = useState<'loading' | 'guest' | 'user'>('loading');

  useEffect(() => {
    // Solo se ejecuta en el cliente: recupera la sesión guardada en caché (localStorage)
    let hasToken = false;
    try {
      hasToken = !!localStorage.getItem('umt_token');
    } catch {
      hasToken = false;
    }
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setAuthState(hasToken ? 'user' : 'guest');
  }, []);

  function handleSessionChange(hasSession: boolean) {
    setAuthState(hasSession ? 'user' : 'guest');
    if (!hasSession) {
      try {
        localStorage.removeItem('umt_token');
      } catch {
        /* noop */
      }
    }
  }

  const header = (
    <header className="nav shell">
      <a className="brand" href="#top">
        <span>U</span> Universal Media Tracker
      </a>
      <a className="navLink" href="#dashboard">
        Mi biblioteca
      </a>
    </header>
  );

  // Mientras se resuelve la sesión (SSR y primer render del cliente), mostramos el mismo
  // marcador minimalista para evitar errores de hidratación.
  if (authState === 'loading') {
    return (
      <main>
        {header}
        <div style={{ minHeight: '100vh' }} />
      </main>
    );
  }

  // Usuario autenticado: nunca mostrar la landing, solo su panel.
  if (authState === 'user') {
    return (
      <main>
        {header}
        <Dashboard onSessionChange={handleSessionChange} />
      </main>
    );
  }

  // Visitante sin sesión: landing + panel de acceso.
  return (
    <main>
      {header}
      <section className="hero shell" id="top">
        <div>
          <p className="eyebrow">Una biblioteca. Todas tus historias.</p>
          <h1>Recuerda cada mundo que has visitado.</h1>
          <p className="lead">
            Películas, anime, libros, manga, música y más. Sincroniza tu progreso desde cualquier
            dispositivo.
          </p>
          <a className="primaryButton" href="#dashboard">
            Empezar
          </a>
        </div>
        <div className="orbit" aria-hidden="true">
          <div className="orbitalCard cardOne">
            映画
            <br />
            <strong>Anime</strong>
          </div>
          <div className="orbitalCard cardTwo">
            II
            <br />
            <strong>Libros</strong>
          </div>
          <div className="orbitalCard cardThree">
            ♪
            <br />
            <strong>Música</strong>
          </div>
          <div className="glow" />
        </div>
      </section>
      <Dashboard onSessionChange={handleSessionChange} />
    </main>
  );
}
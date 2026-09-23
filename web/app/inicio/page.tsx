import Link from 'next/link';

export default function LandingPage() {
  return (
    <main>
      <header className="nav shell">
        <Link className="brand" href="/inicio"><span>U</span> Universal Media Tracker</Link>
        <nav style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
          <Link className="navLink" href="/acceso">Iniciar sesión</Link>
          <Link className="primaryButton" href="/acceso">Crear cuenta</Link>
        </nav>
      </header>
      <section className="hero shell">
        <div>
          <p className="eyebrow">Una biblioteca. Todas tus historias.</p>
          <h1>Recuerda cada mundo que has visitado.</h1>
          <p className="lead">
            Películas, anime, libros, manga, música, cómics y videojuegos. Organiza tu progreso y descubre qué ver, leer, escuchar o jugar después.
          </p>
          <Link className="primaryButton" href="/acceso">Empezar</Link>
        </div>
        <div className="orbit" aria-label="Tipos de contenido disponibles">
          <div className="orbitalCard cardOne">CINE<br /><strong>Películas y series</strong></div>
          <div className="orbitalCard cardTwo">LECTURA<br /><strong>Libros y cómics</strong></div>
          <div className="orbitalCard cardThree">AUDIO<br /><strong>Música y álbumes</strong></div>
          <div className="glow" />
        </div>
      </section>
    </main>
  );
}

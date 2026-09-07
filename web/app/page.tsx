import { Dashboard } from "@/components/dashboard";

export default function Home() {
  return (
    <main>
      <header className="nav shell">
        <a className="brand" href="#top" aria-label="Universal Media Tracker">
          <span>U</span> Universal
        </a>
        <a className="navLink" href="#dashboard">Mi biblioteca</a>
      </header>
      <section className="hero shell" id="top">
        <div>
          <p className="eyebrow">Una biblioteca. Todas tus historias.</p>
          <h1>Recuerda cada mundo que has visitado.</h1>
          <p className="lead">
            Películas, anime, libros, música y todo lo que venga después. Organiza tu progreso
            desde cualquier pantalla.
          </p>
          <a className="primaryButton" href="#dashboard">Empezar ahora</a>
        </div>
        <div className="orbit" aria-hidden="true">
          <div className="orbitalCard cardOne">映画<br /><strong>Anime</strong></div>
          <div className="orbitalCard cardTwo">II<br /><strong>Libros</strong></div>
          <div className="orbitalCard cardThree">♪<br /><strong>Música</strong></div>
          <div className="glow" />
        </div>
      </section>
      <Dashboard />
    </main>
  );
}

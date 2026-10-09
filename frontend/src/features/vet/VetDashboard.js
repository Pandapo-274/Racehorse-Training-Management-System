import "./vet.css";
import { useNavigate } from "react-router-dom";
import { logout } from "../auth/authService";
import { ProfileCard, ProfileCircle } from "../profile/ProfileLink";

// TODO(fetch): horses -> API sơ đồ chuồng + trạng thái từng ngựa (StableMap)
const horses = [
  { stall: "01", name: "Symboli Rudolf", status: "fit" },
  { stall: "02", name: "Gold Ship", status: "fit" },
  { stall: "03", name: "TM Opera O", status: "monitor" },
  { stall: "04", name: "Kitasan Black", status: "fit" },
  { stall: "05", name: "Deep Impact", status: "fit" },

  { stall: "06", name: "Daiwa Scarlet", status: "fit" },
  { stall: "07", name: "Air Groove", status: "injured" },
  { stall: "08", name: "Almond Eye", status: "fit" },
  { stall: "09", name: "Orfevre", status: "fit" },
  { stall: "10", name: "Narita Brian", status: "monitor" },

  { stall: "11", name: "Oguri Cap", status: "fit" },
  { stall: "12", name: "Tokai Teio", status: "fit" },
  { stall: "13", name: "Special Week", status: "fit" },
  { stall: "14", name: "Vodka", status: "quarantine" },
  { stall: "15", name: "Mejiro Ryan", status: "fit" },

  { stall: "16", name: "Grass Wonder", status: "fit" },
  { stall: "17", name: "El Condor Pasa", status: "fit" },
  { stall: "18", name: "Biwa Hayahide", status: "monitor" },
  { stall: "19", name: "Hishi Amazon", status: "fit" },
  { stall: "20", name: "Maruzensky", status: "fit" },
];

// TODO(fetch): waitingHorses -> API ngựa đang chờ khám (WaitingList)
const waitingHorses = [
  {
    name: "Air Groove",
    description: "Lame, left hind",
    status: "urgent",
  },
  {
    name: "TM Opera O",
    description: "Follow-up after treatment",
    status: "normal",
  },
  {
    name: "Gold Ship",
    description: "Routine hoof check",
    status: "normal",
  },
];

function VetSidebar() {
  const navigate = useNavigate();
  const handleSignOut = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <aside className="vet-sidebar">

      <div className="vet-brand">
        <img src="/logo.svg" alt="Tenma Racing Academy" />

        <div>
          <strong>TENMA</strong>
          <span>Racing Academy</span>
        </div>
      </div>

      <div className="vet-menu-title">
        ◉ MENU
      </div>

      <nav className="vet-navigation">

        <button type="button" className="vet-nav-item active" onClick={() => navigate("/veterinarian/horses")}>
          <span>◉</span>
          Herd health
        </button>

        <button className="vet-nav-item">
          <span>◉</span>
          Examination
        </button>

        <button className="vet-nav-item">
          <span>◉</span>
          Recovery
        </button>

        <button className="vet-nav-item">
          <span>◉</span>
          Preventive care
        </button>

      </nav>

      <div className="vet-sidebar-bottom">

        <button className="vet-signout" onClick={handleSignOut}>
          Sign out
        </button>

        {/* Trước đây ghi cứng "Dr. Yuki Nakajima" nên ai đăng nhập cũng thấy
            tên đó. Giờ lấy theo người đang đăng nhập, và bấm vào mở hồ sơ. */}
        <ProfileCard className="vet-user" avatarClassName="vet-avatar" />

      </div>

    </aside>
  );
}


function StableMap() {
  return (
    <div className="vet-card stable-card">

      <div className="vet-card-title">

        <span>◆</span>

        <div>
          <h2>Stable map</h2>
          <p>Stall colour shows health status</p>
        </div>

      </div>

      <div className="stable-grid">

        {horses.map((horse) => (
          <div
            key={horse.stall}
            className={`stable-box ${horse.status}`}
          >

            <span className="stall-number">
              Stall {horse.stall}
            </span>

            <strong>
              {horse.name}
            </strong>

          </div>
        ))}

      </div>

    </div>
  );
}


function Legend() {
  return (
    <div className="vet-card legend-card">

      <div className="vet-card-title">

        <span>◆</span>

        <div>
          <h2>Legend</h2>
        </div>

      </div>

      <div className="legend-list">

        <div>
          <span className="legend-color fit" />
          <strong>Race-fit</strong>
        </div>

        <div>
          <span className="legend-color monitor" />
          <strong>Monitor</strong>
        </div>

        <div>
          <span className="legend-color injured" />
          <strong>Injured</strong>
        </div>

        <div>
          <span className="legend-color quarantine" />
          <strong>Quarantine</strong>
        </div>

      </div>

    </div>
  );
}


function WaitingList() {
  return (
    <div className="vet-card waiting-card">

      <div className="vet-card-title">

        <span>◆</span>

        <div>
          <h2>Waiting to be seen</h2>
        </div>

      </div>

      <div className="waiting-list">

        {waitingHorses.map((horse) => (

          <div
            key={horse.name}
            className={`waiting-item ${horse.status}`}
          >

            <strong>
              {horse.name}
            </strong>

            <span>
              {horse.description}
            </span>

          </div>

        ))}

      </div>

    </div>
  );
}


export default function VetDashboard() {

  return (
    <div className="vet-page">

      <VetSidebar />

      <main className="vet-main">

        {/* HEADER */}

        <header className="vet-header">

          <div>

            <div className="vet-page-title">

              <span>◆</span>

              <h1>
                Herd health board
              </h1>

            </div>

            <p>
              Stable map · Barns A – C
            </p>

          </div>


          <div className="vet-header-actions">

            <input
              type="text"
              className="vet-search"
              placeholder="Search horses, records..."
            />

            <button className="vet-notification">
              ●
            </button>

            <ProfileCircle className="vet-profile" />

          </div>

        </header>


        <section className="vet-content">

          {/* ALERT */}

          <div className="vet-alert">

            <strong>
              New incident report:
            </strong>

            <span>
              Air Groove is lame with swelling in the left hind hock.
            </span>

          </div>


          {/* MAIN AREA */}

          <div className="vet-dashboard-grid">

            <StableMap />

            <div className="vet-right-column">

              <Legend />

              <WaitingList />

            </div>

          </div>


          {/* ACTION */}

          <div className="vet-action">

            <button className="examine-button">

              <span>
                Examine Air Groove now
              </span>

            </button>

          </div>

        </section>

      </main>

    </div>
  );
}
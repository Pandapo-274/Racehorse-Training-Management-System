// src/features/horse/PedigreeTree.js
// UC7 - cây phả hệ.
//
// Vẽ theo lối bảng phả hệ truyền thống: con ngựa đứng cột trái, mỗi đời tổ
// tiên là một cột kế bên, cha ở nửa trên và mẹ ở nửa dưới của khoảng mà nút
// con chiếm. Nhờ vậy vị trí theo chiều dọc tự nó mang nghĩa - cứ đi lên là
// dòng cha, đi xuống là dòng mẹ - và mắt lần được một dòng máu mà không cần
// chú giải.
//
// Cách đặt: lưới CSS với số hàng bằng 2^(số đời). Một nút ở đời g chiếm
// 2^(depth-g) hàng, nên nó luôn nằm giữa đúng hai nút con của nó.
//
// Ô trống KHÔNG bị bỏ đi mà hiện thành "Unknown": nếu bỏ, cái khung bị méo và
// người đọc không biết đang thiếu nhánh nào.

const GENDER_MARK = {
  COLT: "♂", STALLION: "♂",
  FILLY: "♀", MARE: "♀",
  GELDING: "♦",
};

/** Số đời thật sự có dữ liệu, để không vẽ thừa cột rỗng. */
function measure(node) {
  if (!node) return -1;
  return 1 + Math.max(measure(node.sire), measure(node.dam));
}

/** Duyệt cây thành danh sách phẳng kèm đời và chỉ số hàng trong đời đó. */
function flatten(node, depth) {
  const out = [];

  const walk = (n, gen, index) => {
    if (gen > depth) return;
    out.push({ node: n, gen, index });
    if (gen === depth) return;
    walk(n?.sire || null, gen + 1, index * 2);
    walk(n?.dam || null, gen + 1, index * 2 + 1);
  };

  walk(node, 0, 0);
  return out;
}

export default function PedigreeTree({ root }) {
  if (!root) return null;

  const depth = Math.max(0, measure(root));
  const rows = 2 ** depth;
  const cells = flatten(root, depth);

  return (
    <div className="hz-pedigree-scroll">
      <div
        className="hz-pedigree"
        style={{
          gridTemplateColumns: `repeat(${depth + 1}, minmax(150px, 1fr))`,
          gridTemplateRows: `repeat(${rows}, minmax(52px, auto))`,
        }}
      >
        {cells.map(({ node, gen, index }) => {
          const span = 2 ** (depth - gen);
          const style = {
            gridColumn: gen + 1,
            gridRow: `${index * span + 1} / span ${span}`,
          };

          if (!node) {
            return (
              <div
                className="hz-ped-node hz-ped-node--empty"
                style={style}
                key={`empty-${gen}-${index}`}
              >
                Unknown
              </div>
            );
          }

          // Nửa trên của mỗi cặp là dòng cha, nửa dưới là dòng mẹ. Đời 0 là
          // chính con ngựa nên không mang nhãn nào.
          const line = gen === 0 ? null : index % 2 === 0 ? "Sire line" : "Dam line";

          return (
            <div
              className={`hz-ped-node${gen === 0 ? " hz-ped-node--self" : ""}`}
              style={style}
              key={node.horseId}
            >
              <strong>
                {node.horseName}{" "}
                {GENDER_MARK[node.horseGender] && (
                  <i aria-hidden="true">{GENDER_MARK[node.horseGender]}</i>
                )}
              </strong>
              <span className="hz-mono">{node.registrationCode}</span>
              {line && <small>{line}</small>}
            </div>
          );
        })}
      </div>
    </div>
  );
}

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class Main {
    private static final Color NAVY = new Color(19, 35, 62);
    private static final Color BLUE = new Color(42, 99, 220);
    private static final Color BG = new Color(245, 247, 251);
    private static final Color TEXT = new Color(32, 43, 60);
    private final JFrame frame = new JFrame("Waypoint");
    private final CardLayout screens = new CardLayout();
    private final JPanel root = new JPanel(screens);
    private String selectedRole = "Jobseeker";

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().start());
    }

    private void start() {
        UIManager.put("Button.font", new Font("Segoe UI", Font.BOLD, 14));
        UIManager.put("Label.font", new Font("Segoe UI", Font.PLAIN, 14));
        UIManager.put("TextField.font", new Font("Segoe UI", Font.PLAIN, 14));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1100, 720));
        frame.setSize(1280, 800);
        frame.setLocationRelativeTo(null);
        root.add(loginPage(), "login");
        root.add(adminPage(), "admin");
        frame.setContentPane(root);
        frame.setVisible(true);
    }

    private JPanel loginPage() {
        JPanel page = new JPanel(new GridLayout(1, 2));
        page.add(brandPanel());

        JPanel right = new JPanel(new GridBagLayout());
        right.setBackground(Color.WHITE);
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setPreferredSize(new Dimension(450, 560));
        JLabel welcome = title("Welcome back", 32);
        JLabel sub = muted("Choose your portal and sign in to continue.");
        form.add(welcome); form.add(Box.createVerticalStrut(8)); form.add(sub);
        form.add(Box.createVerticalStrut(32));

        JPanel roles = new JPanel(new GridLayout(1, 3, 10, 0));
        roles.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        JToggleButton jobseeker = roleButton("Jobseeker", true, group);
        JToggleButton myhr = roleButton("MyHR", false, group);
        JToggleButton admin = roleButton("Admin", false, group);
        roles.add(jobseeker); roles.add(myhr); roles.add(admin);
        for (JToggleButton b : new JToggleButton[]{jobseeker, myhr, admin}) {
            b.addActionListener(e -> selectedRole = b.getText());
        }
        form.add(roles); form.add(Box.createVerticalStrut(28));
        form.add(fieldLabel("Email address")); form.add(Box.createVerticalStrut(8));
        JTextField email = input("name@company.com"); form.add(email);
        form.add(Box.createVerticalStrut(18)); form.add(fieldLabel("Password"));
        form.add(Box.createVerticalStrut(8)); JPasswordField password = new JPasswordField();
        styleInput(password); form.add(password); form.add(Box.createVerticalStrut(10));
        JButton forgot = linkButton("Forgot password?"); forgot.setAlignmentX(Component.RIGHT_ALIGNMENT);
        form.add(forgot); form.add(Box.createVerticalStrut(18));
        JButton signIn = primaryButton("Sign in");
        signIn.addActionListener(e -> {
            if (selectedRole.equals("Admin")) screens.show(root, "admin");
            else JOptionPane.showMessageDialog(frame, selectedRole + " portal selected.\nThis prototype currently includes the Admin dashboard.", "Waypoint", JOptionPane.INFORMATION_MESSAGE);
        });
        form.add(signIn); form.add(Box.createVerticalStrut(22));
        JLabel hint = muted("Prototype: select Admin and click Sign in — no credentials required.");
        hint.setAlignmentX(Component.CENTER_ALIGNMENT); form.add(hint);
        right.add(form);
        page.add(right);
        return page;
    }

    private JPanel brandPanel() {
        JPanel panel = new JPanel(new GridBagLayout()); panel.setBackground(NAVY);
        JPanel content = new JPanel(); content.setOpaque(false); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JLabel mark = new JLabel("W"); mark.setForeground(Color.WHITE); mark.setFont(new Font("Segoe UI", Font.BOLD, 38));
        mark.setBorder(new CompoundBorder(new LineBorder(new Color(99, 150, 255), 2, true), new EmptyBorder(6, 15, 8, 15)));
        JLabel name = new JLabel("Waypoint"); name.setForeground(Color.WHITE); name.setFont(new Font("Segoe UI", Font.BOLD, 38));
        JLabel tagline = new JLabel("One platform. Every career journey."); tagline.setForeground(new Color(188, 204, 232)); tagline.setFont(new Font("Segoe UI", Font.PLAIN, 18));
        JLabel detail = new JLabel("<html>Connect talent, HR teams, and opportunities<br>through one simple, secure workspace.</html>");
        detail.setForeground(new Color(151, 170, 203)); detail.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        content.add(mark); content.add(Box.createVerticalStrut(24)); content.add(name); content.add(Box.createVerticalStrut(12)); content.add(tagline); content.add(Box.createVerticalStrut(22)); content.add(detail);
        panel.add(content); return panel;
    }

    private JPanel adminPage() {
        JPanel page = new JPanel(new BorderLayout());
        JPanel content = new JPanel(new CardLayout()); content.setBackground(BG);
        Map<String, JPanel> pages = new LinkedHashMap<>();
        pages.put("Dashboard", dashboard()); pages.put("Users", usersPage()); pages.put("Jobs", jobsPage());
        pages.put("Approvals", simplePage("Approvals", "Review pending employers, job postings, and access requests."));
        pages.put("Reports", simplePage("Reports & analytics", "Monitor hiring activity and platform performance."));
        pages.put("Settings", simplePage("Platform settings", "Manage security, notifications, and portal configuration."));
        pages.forEach(content::add);
        page.add(sidebar(content), BorderLayout.WEST); page.add(content, BorderLayout.CENTER);
        return page;
    }

    private JPanel sidebar(JPanel content) {
        JPanel side = new JPanel(); side.setPreferredSize(new Dimension(235, 0)); side.setBackground(NAVY); side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS)); side.setBorder(new EmptyBorder(28, 18, 24, 18));
        JLabel logo = new JLabel("  W  Waypoint"); logo.setForeground(Color.WHITE); logo.setFont(new Font("Segoe UI", Font.BOLD, 22)); logo.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(logo); side.add(Box.createVerticalStrut(42));
        ButtonGroup nav = new ButtonGroup();
        for (String item : new String[]{"Dashboard", "Users", "Jobs", "Approvals", "Reports", "Settings"}) {
            JToggleButton button = new JToggleButton(item); button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48)); button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setForeground(new Color(205, 216, 235)); button.setBackground(NAVY); button.setBorder(new EmptyBorder(0, 18, 0, 10)); button.setFocusPainted(false);
            button.setContentAreaFilled(false); button.setOpaque(true); nav.add(button); side.add(button); side.add(Box.createVerticalStrut(5));
            button.addActionListener(e -> ((CardLayout) content.getLayout()).show(content, item));
            if (item.equals("Dashboard")) button.setSelected(true);
        }
        side.add(Box.createVerticalGlue()); JLabel admin = new JLabel("  Admin User"); admin.setForeground(Color.WHITE); admin.setFont(new Font("Segoe UI", Font.BOLD, 14)); side.add(admin);
        JButton logout = linkButton("Sign out"); logout.setForeground(new Color(160, 186, 232)); logout.addActionListener(e -> screens.show(root, "login")); side.add(logout);
        return side;
    }

    private JPanel dashboard() {
        JPanel body = contentBase("Admin dashboard", "Welcome back. Here’s what’s happening across Waypoint today.");
        JPanel cards = new JPanel(new GridLayout(1, 4, 16, 0)); cards.setOpaque(false); cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 125));
        cards.add(metric("Total users", "12,480", "+8.2% this month", new Color(42, 99, 220)));
        cards.add(metric("Active jobs", "1,284", "+32 this week", new Color(21, 158, 119)));
        cards.add(metric("Pending approvals", "24", "Needs attention", new Color(234, 139, 36)));
        cards.add(metric("Applications", "8,629", "+12.4% this month", new Color(123, 80, 203)));
        body.add(cards); body.add(Box.createVerticalStrut(22));
        JPanel lower = new JPanel(new GridLayout(1, 2, 18, 0)); lower.setOpaque(false); lower.setMaximumSize(new Dimension(Integer.MAX_VALUE, 330));
        lower.add(activityPanel()); lower.add(quickActions()); body.add(lower);
        return wrap(body);
    }

    private JPanel metric(String label, String value, String note, Color accent) {
        JPanel p = card(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = muted(label); JLabel v = title(value, 28); JLabel n = new JLabel(note); n.setForeground(accent); n.setFont(new Font("Segoe UI", Font.BOLD, 12));
        p.add(l); p.add(Box.createVerticalStrut(10)); p.add(v); p.add(Box.createVerticalStrut(8)); p.add(n); return p;
    }

    private JPanel activityPanel() {
        JPanel p = card(); p.setLayout(new BorderLayout(0, 14)); p.add(title("Recent activity", 18), BorderLayout.NORTH);
        JPanel list = new JPanel(); list.setOpaque(false); list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        String[][] rows={{"New employer registered", "Northstar Technologies · 8 min ago"},{"Job submitted for approval", "Senior Product Designer · 24 min ago"},{"User account verified", "Maya Rodriguez · 1 hour ago"},{"Job posting published", "Data Engineer · 2 hours ago"}};
        for (String[] row : rows) { JLabel a=new JLabel(row[0]); a.setFont(new Font("Segoe UI",Font.BOLD,14)); a.setForeground(TEXT); JLabel b=muted(row[1]); list.add(a); list.add(Box.createVerticalStrut(4)); list.add(b); list.add(Box.createVerticalStrut(17)); }
        p.add(list, BorderLayout.CENTER); return p;
    }

    private JPanel quickActions() {
        JPanel p=card(); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS)); p.add(title("Quick actions",18)); p.add(Box.createVerticalStrut(18));
        for(String s:new String[]{"Review pending approvals", "Add a new administrator", "Create job category", "Download monthly report"}){ JButton b=secondaryButton(s); b.setMaximumSize(new Dimension(Integer.MAX_VALUE,46)); b.addActionListener(e->JOptionPane.showMessageDialog(frame,s+" opened in prototype mode.")); p.add(b); p.add(Box.createVerticalStrut(10)); }
        return p;
    }

    private JPanel usersPage() {
        JPanel b=contentBase("User management","Search, review, and manage every Waypoint account.");
        JPanel tools=new JPanel(new BorderLayout(12,0)); tools.setOpaque(false); JTextField search=input("Search users..."); JButton add=primaryButton("+ Add user"); add.setPreferredSize(new Dimension(130,44)); add.addActionListener(e->JOptionPane.showMessageDialog(frame,"New user form opened.")); tools.add(search); tools.add(add,BorderLayout.EAST); tools.setMaximumSize(new Dimension(Integer.MAX_VALUE,44)); b.add(tools); b.add(Box.createVerticalStrut(20));
        b.add(table(new String[]{"Name","Role","Email","Status","Joined"},new Object[][]{{"Maya Rodriguez","Jobseeker","maya.r@example.com","Active","Aug 18, 2026"},{"Daniel Kim","MyHR","daniel@northstar.io","Active","Aug 17, 2026"},{"Ava Thompson","Jobseeker","ava.t@example.com","Pending","Aug 16, 2026"},{"Noah Williams","MyHR","noah@veridian.com","Active","Aug 14, 2026"},{"Sophia Chen","Admin","sophia@waypoint.com","Active","Aug 10, 2026"}})); return wrap(b);
    }

    private JPanel jobsPage() {
        JPanel b=contentBase("Job management","Manage published, draft, and pending job postings.");
        b.add(table(new String[]{"Job title","Company","Location","Status","Applications"},new Object[][]{{"Senior Product Designer","Northstar Technologies","Chicago, IL","Pending","0"},{"Data Engineer","Veridian Labs","Remote","Published","142"},{"HR Business Partner","Atlas Group","Dallas, TX","Published","86"},{"Frontend Developer","Juniper Health","Austin, TX","Draft","0"},{"Financial Analyst","Stonebridge","New York, NY","Published","63"}})); return wrap(b);
    }

    private JPanel simplePage(String heading,String description){ JPanel b=contentBase(heading,description); JPanel empty=card(); empty.setLayout(new GridBagLayout()); JLabel l=muted("Select an item from this area to continue."); l.setFont(new Font("Segoe UI",Font.PLAIN,16)); empty.add(l); empty.setMaximumSize(new Dimension(Integer.MAX_VALUE,350)); b.add(empty); return wrap(b); }
    private JPanel contentBase(String heading,String description){ JPanel b=new JPanel(); b.setOpaque(false); b.setLayout(new BoxLayout(b,BoxLayout.Y_AXIS)); b.add(title(heading,28)); b.add(Box.createVerticalStrut(7)); b.add(muted(description)); b.add(Box.createVerticalStrut(28)); return b; }
    private JPanel wrap(JPanel body){ JPanel w=new JPanel(new BorderLayout()); w.setBackground(BG); w.setBorder(new EmptyBorder(38,42,38,42)); w.add(body,BorderLayout.NORTH); return w; }
    private JScrollPane table(String[] cols,Object[][] data){ JTable t=new JTable(new DefaultTableModel(data,cols){public boolean isCellEditable(int r,int c){return false;}}); t.setRowHeight(48); t.setShowVerticalLines(false); t.setGridColor(new Color(232,236,243)); t.getTableHeader().setPreferredSize(new Dimension(0,42)); t.getTableHeader().setFont(new Font("Segoe UI",Font.BOLD,13)); t.setFont(new Font("Segoe UI",Font.PLAIN,13)); JScrollPane s=new JScrollPane(t); s.setBorder(new LineBorder(new Color(225,230,239))); s.setMaximumSize(new Dimension(Integer.MAX_VALUE,330)); s.setPreferredSize(new Dimension(800,300)); return s; }
    private JPanel card(){ JPanel p=new JPanel(); p.setBackground(Color.WHITE); p.setBorder(new CompoundBorder(new LineBorder(new Color(229,233,241)),new EmptyBorder(20,22,20,22))); return p; }
    private JLabel title(String text,int size){ JLabel l=new JLabel(text); l.setForeground(TEXT); l.setFont(new Font("Segoe UI",Font.BOLD,size)); l.setAlignmentX(Component.LEFT_ALIGNMENT); return l; }
    private JLabel muted(String text){ JLabel l=new JLabel(text); l.setForeground(new Color(105,117,136)); l.setAlignmentX(Component.LEFT_ALIGNMENT); return l; }
    private JLabel fieldLabel(String text){ JLabel l=new JLabel(text); l.setForeground(TEXT); l.setFont(new Font("Segoe UI",Font.BOLD,13)); l.setAlignmentX(Component.LEFT_ALIGNMENT); return l; }
    private JTextField input(String placeholder){ JTextField f=new JTextField(placeholder); f.setForeground(new Color(130,140,155)); styleInput(f); return f; }
    private void styleInput(JTextField f){ f.setMaximumSize(new Dimension(Integer.MAX_VALUE,46)); f.setPreferredSize(new Dimension(300,46)); f.setBorder(new CompoundBorder(new LineBorder(new Color(207,214,226)),new EmptyBorder(0,13,0,13))); }
    private JToggleButton roleButton(String text,boolean selected,ButtonGroup group){ JToggleButton b=new JToggleButton(text,selected); b.setPreferredSize(new Dimension(120,52)); b.setFocusPainted(false); group.add(b); return b; }
    private JButton primaryButton(String text){ JButton b=new JButton(text); b.setForeground(Color.WHITE); b.setBackground(BLUE); b.setFocusPainted(false); b.setBorder(new EmptyBorder(13,18,13,18)); b.setAlignmentX(Component.LEFT_ALIGNMENT); b.setMaximumSize(new Dimension(Integer.MAX_VALUE,46)); return b; }
    private JButton secondaryButton(String text){ JButton b=new JButton(text); b.setForeground(TEXT); b.setBackground(Color.WHITE); b.setFocusPainted(false); b.setHorizontalAlignment(SwingConstants.LEFT); b.setBorder(new CompoundBorder(new LineBorder(new Color(219,225,234)),new EmptyBorder(10,13,10,13))); return b; }
    private JButton linkButton(String text){ JButton b=new JButton(text); b.setBorderPainted(false); b.setContentAreaFilled(false); b.setFocusPainted(false); b.setForeground(BLUE); return b; }
}
